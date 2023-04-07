import React, { useState } from 'react';
import { Box, FormHelperText, Typography } from '@material-ui/core';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import { array, mixed, object, string } from 'yup';
import { yupResolver } from '@hookform/resolvers/yup';
import { toast } from 'react-toastify';

import { NTPSetupType, ProviderCode } from '../../constants';
import { NTP_SERVER_REGEX } from '../constants';
import {
  ConfigureOnPremRegionModal,
  ConfigureOnPremRegionFormValues
} from '../configureRegion/ConfigureOnPremRegionModal';
import { ACCEPTABLE_CHARS } from '../../../../config/constants';
import { DeleteRegionModal } from '../../components/DeleteRegionModal';
import { FieldGroup } from '../components/FieldGroup';
import { FieldLabel } from '../components/FieldLabel';
import { FormContainer } from '../components/FormContainer';
import { FormField } from '../components/FormField';
import { NTPConfigField } from '../../components/NTPConfigField';
import { RegionList } from '../../components/RegionList';
import { RegionOperation } from '../configureRegion/constants';
import { YBButton } from '../../../../common/forms/fields';
import { YBDropZoneField } from '../../components/YBDropZone/YBDropZoneField';
import { YBInput, YBInputField, YBToggleField } from '../../../../../redesign/components';
import {
  addItem,
  deleteItem,
  editItem,
  generateLowerCaseAlphanumericId,
  readFileAsText
} from '../utils';
import { EditProvider } from '../ProviderEditView';
import { getNtpSetupType } from '../../utils';
import { VersionWarningBanner } from '../components/VersionWarningBanner';

import { OnPremProvider, OnPremRegionMutation, YBProviderMutation } from '../../types';

interface OnPremProviderEditFormProps {
  editProvider: EditProvider;
  isProviderInUse: boolean;
  providerConfig: OnPremProvider;
}

interface OnPremProviderEditFormFieldValues {
  dbNodePublicInternetAccess: boolean;
  editSSHKeypair: boolean;
  installNodeExporter: boolean;
  ntpServers: string[];
  ntpSetupType: NTPSetupType;
  providerName: string;
  regions: ConfigureOnPremRegionFormValues[];
  skipProvisioning: boolean;
  sshKeypairName: string;
  sshPort: number;
  sshPrivateKeyContent: File;
  sshUser: string;
  version: number;

  nodeExporterPort?: number;
  nodeExporterUser?: string;
  ybHomeDir?: string;
}

const VALIDATION_SCHEMA = object().shape({
  providerName: string()
    .required('Provider Name is required.')
    .matches(
      ACCEPTABLE_CHARS,
      'Provider name cannot contain special characters other than "-", and "_"'
    ),
  sshUser: string().required('SSH user is required.'),
  sshPrivateKeyContent: mixed().when('editSSHKeypair', {
    is: true,
    then: mixed().required('SSH private key is required.')
  }),
  ntpServers: array().when('ntpSetupType', {
    is: NTPSetupType.SPECIFIED,
    then: array().of(
      string().matches(
        NTP_SERVER_REGEX,
        (testContext) =>
          `NTP servers must be provided in IPv4, IPv6, or hostname format. '${testContext.originalValue}' is not valid.`
      )
    )
  }),
  regions: array().min(1, 'Provider configurations must contain at least one region.')
});

export const OnPremProviderEditForm = ({
  editProvider,
  isProviderInUse,
  providerConfig
}: OnPremProviderEditFormProps) => {
  const [isRegionFormModalOpen, setIsRegionFormModalOpen] = useState<boolean>(false);
  const [isDeleteRegionModalOpen, setIsDeleteRegionModalOpen] = useState<boolean>(false);
  const [regionSelection, setRegionSelection] = useState<ConfigureOnPremRegionFormValues>();
  const [regionOperation, setRegionOperation] = useState<RegionOperation>(RegionOperation.ADD);

  const defaultValues = constructDefaultFormValues(providerConfig);
  const formMethods = useForm<OnPremProviderEditFormFieldValues>({
    defaultValues: defaultValues,
    resolver: yupResolver(VALIDATION_SCHEMA)
  });

  const onFormSubmit: SubmitHandler<OnPremProviderEditFormFieldValues> = async (formValues) => {
    if (formValues.ntpSetupType === NTPSetupType.SPECIFIED && !formValues.ntpServers.length) {
      formMethods.setError('ntpServers', {
        type: 'min',
        message: 'Please specify at least one NTP server.'
      });
      return;
    }

    try {
      const providerPayload = await constructProviderPayload(formValues);
      try {
        await editProvider(providerPayload);
      } catch (_) {
        // Handled with `mutateOptions.onError`
      }
    } catch (error) {
      toast.error(error);
    }
  };

  const onFormReset = () => {
    formMethods.reset(defaultValues);
  };
  const showAddRegionFormModal = () => {
    setRegionSelection(undefined);
    setRegionOperation(RegionOperation.ADD);
    setIsRegionFormModalOpen(true);
  };
  const showEditRegionFormModal = () => {
    setRegionOperation(RegionOperation.EDIT);
    setIsRegionFormModalOpen(true);
  };
  const hideRegionFormModal = () => {
    setIsRegionFormModalOpen(false);
  };
  const showDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(true);
  };
  const hideDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(false);
  };

  const regions = formMethods.watch('regions', defaultValues.regions);
  const setRegions = (regions: ConfigureOnPremRegionFormValues[]) =>
    formMethods.setValue('regions', regions);
  const onRegionFormSubmit = (currentRegion: ConfigureOnPremRegionFormValues) => {
    regionOperation === RegionOperation.ADD
      ? addItem(currentRegion, regions, setRegions)
      : editItem(currentRegion, regions, setRegions);
  };
  const onDeleteRegionSubmit = (currentRegion: ConfigureOnPremRegionFormValues) =>
    deleteItem(currentRegion, regions, setRegions);

  const installNodeExporter = formMethods.watch(
    'installNodeExporter',
    defaultValues.installNodeExporter
  );
  const currentProviderVersion = formMethods.watch('version', defaultValues.version);
  const editSSHKeypair = formMethods.watch('editSSHKeypair', defaultValues.editSSHKeypair);
  const isFormDisabled =
    isProviderInUse || formMethods.formState.isValidating || formMethods.formState.isSubmitting;
  return (
    <Box display="flex" justifyContent="center">
      <FormProvider {...formMethods}>
        <FormContainer name="OnPremProviderForm" onSubmit={formMethods.handleSubmit(onFormSubmit)}>
          {currentProviderVersion < providerConfig.version && (
            <VersionWarningBanner onReset={onFormReset} />
          )}
          <Typography variant="h3">Manage OnPrem Provider Configuration</Typography>
          <FormField providerNameField={true}>
            <FieldLabel>Provider Name</FieldLabel>
            <YBInputField
              control={formMethods.control}
              name="providerName"
              fullWidth
              disabled={isFormDisabled}
            />
          </FormField>
          <Box width="100%" display="flex" flexDirection="column" gridGap="32px">
            <FieldGroup
              heading="Regions"
              headerAccessories={
                <YBButton
                  btnIcon="fa fa-plus"
                  btnText="Add Region"
                  btnClass="btn btn-default"
                  btnType="button"
                  onClick={showAddRegionFormModal}
                  disabled={isFormDisabled}
                />
              }
            >
              <RegionList
                providerCode={ProviderCode.ON_PREM}
                regions={regions}
                setRegionSelection={setRegionSelection}
                showAddRegionFormModal={showAddRegionFormModal}
                showEditRegionFormModal={showEditRegionFormModal}
                showDeleteRegionModal={showDeleteRegionModal}
                disabled={isFormDisabled}
                isError={!!formMethods.formState.errors.regions}
              />
              {formMethods.formState.errors.regions?.message ? (
                <FormHelperText error={true}>
                  {formMethods.formState.errors.regions?.message}
                </FormHelperText>
              ) : null}
            </FieldGroup>
            <FieldGroup heading="SSH Key Pairs">
              <FormField>
                <FieldLabel>SSH User</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshUser"
                  fullWidth
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>SSH Port</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshPort"
                  type="number"
                  inputProps={{ min: 0, max: 65535 }}
                  fullWidth
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>Current SSH Keypair Name</FieldLabel>
                <YBInput
                  value={providerConfig.allAccessKeys[0].keyInfo.keyPairName}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Current SSH Private Key</FieldLabel>
                <YBInput
                  value={providerConfig.allAccessKeys[0].keyInfo.privateKey}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Change SSH Keypair</FieldLabel>
                <YBToggleField
                  name="editSSHKeypair"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {editSSHKeypair && (
                <>
                  <FormField>
                    <FieldLabel>SSH Keypair Name</FieldLabel>
                    <YBInputField
                      control={formMethods.control}
                      name="sshKeypairName"
                      disabled={isFormDisabled}
                      fullWidth
                    />
                  </FormField>
                  <FormField>
                    <FieldLabel>SSH Private Key Content</FieldLabel>
                    <YBDropZoneField
                      name="sshPrivateKeyContent"
                      control={formMethods.control}
                      actionButtonText="Upload SSH Key PEM File"
                      multipleFiles={false}
                      showHelpText={false}
                      disabled={isFormDisabled}
                    />
                  </FormField>
                </>
              )}
            </FieldGroup>
            <FieldGroup heading="Advanced">
              <FormField>
                <FieldLabel infoContent="If yes, YBA will install some software packages on the DB nodes by downloading from the public internet. If not, all installation of software on the nodes will download from only this YBA instance.">
                  DB Nodes have public internet access?
                </FieldLabel>
                <YBToggleField
                  name="dbNodePublicInternetAccess"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel infoContent="If enabled, node provisioning will not be done when the universe is created. A pre-provision script will be provided to be run manually instead.">
                  Manually Provision Nodes
                </FieldLabel>
                <YBToggleField
                  name="skipProvisioning"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>YB Nodes Home Directory (Optional)</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="ybHomeDir"
                  fullWidth
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>Install Node Exporter</FieldLabel>
                <YBToggleField
                  name="installNodeExporter"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {installNodeExporter && (
                <FormField>
                  <FieldLabel>Node Exporter User</FieldLabel>
                  <YBInputField
                    control={formMethods.control}
                    name="nodeExporterUser"
                    fullWidth
                    disabled={isFormDisabled}
                  />
                </FormField>
              )}
              <FormField>
                <FieldLabel>Node Exporter Port</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="nodeExporterPort"
                  type="number"
                  fullWidth
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>NTP Setup</FieldLabel>
                <NTPConfigField isDisabled={isFormDisabled} providerCode={ProviderCode.ON_PREM} />
              </FormField>
            </FieldGroup>
          </Box>
          <Box marginTop="16px">
            <YBButton
              btnText="Apply Changes"
              btnClass="btn btn-default save-btn"
              btnType="submit"
              disabled={isFormDisabled}
            />
            <YBButton
              btnText="Clear Changes"
              btnClass="btn btn-default"
              onClick={onFormReset}
              disabled={isFormDisabled}
            />
          </Box>
        </FormContainer>
      </FormProvider>
      {isRegionFormModalOpen && (
        <ConfigureOnPremRegionModal
          configuredRegions={regions}
          onClose={hideRegionFormModal}
          onRegionSubmit={onRegionFormSubmit}
          open={isRegionFormModalOpen}
          regionSelection={regionSelection}
        />
      )}
      {isDeleteRegionModalOpen && (
        <DeleteRegionModal
          region={regionSelection}
          onClose={hideDeleteRegionModal}
          open={isDeleteRegionModalOpen}
          deleteRegion={onDeleteRegionSubmit}
        />
      )}
    </Box>
  );
};

const constructDefaultFormValues = (
  providerConfig: OnPremProvider
): Partial<OnPremProviderEditFormFieldValues> => ({
  dbNodePublicInternetAccess: !providerConfig.details.airGapInstall,
  editSSHKeypair: false,
  installNodeExporter: !!providerConfig.details.installNodeExporter,
  nodeExporterPort: providerConfig.details.nodeExporterPort,
  nodeExporterUser: providerConfig.details.nodeExporterUser,
  ntpServers: providerConfig.details.ntpServers,
  ntpSetupType: getNtpSetupType(providerConfig),
  providerName: providerConfig.name,
  regions: providerConfig.regions.map((region) => ({
    fieldId: generateLowerCaseAlphanumericId(),
    code: region.code,
    zones: region.zones.map((zone) => ({
      code: zone.code
    }))
  })),
  skipProvisioning: providerConfig.details.skipProvisioning,
  sshPort: providerConfig.details.sshPort,
  sshUser: providerConfig.details.sshUser,
  version: providerConfig.version,
  ybHomeDir: providerConfig.details.cloudInfo.onprem.ybHomeDir
});

const constructProviderPayload = async (
  formValues: OnPremProviderEditFormFieldValues
): Promise<YBProviderMutation> => {
  let sshPrivateKeyContent = '';
  try {
    sshPrivateKeyContent = formValues.sshPrivateKeyContent
      ? (await readFileAsText(formValues.sshPrivateKeyContent)) ?? ''
      : '';
  } catch (error) {
    throw new Error(`An error occurred while processing the SSH private key file: ${error}`);
  }

  return {
    code: ProviderCode.ON_PREM,
    name: formValues.providerName,
    allAccessKeys: [
      {
        keyInfo: {
          ...(formValues.sshKeypairName && { keyPairName: formValues.sshKeypairName }),
          ...(formValues.sshPrivateKeyContent && {
            sshPrivateKeyContent: sshPrivateKeyContent
          })
        }
      }
    ],
    details: {
      airGapInstall: !formValues.dbNodePublicInternetAccess,
      cloudInfo: {
        [ProviderCode.ON_PREM]: {
          ybHomeDir: formValues.ybHomeDir
        }
      },
      ntpServers: formValues.ntpServers,
      setUpChrony: formValues.ntpSetupType !== NTPSetupType.NO_NTP,
      sshPort: formValues.sshPort,
      sshUser: formValues.sshUser
    },
    regions: formValues.regions.map<OnPremRegionMutation>((regionFormValues) => ({
      code: regionFormValues.code,
      name: regionFormValues.code,
      zones: regionFormValues.zones.map((zone) => ({ code: zone.code, name: zone.code }))
    })),
    version: formValues.version
  };
};
