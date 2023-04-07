import React, { useState } from 'react';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import { Box, CircularProgress, FormHelperText, Typography } from '@material-ui/core';
import { yupResolver } from '@hookform/resolvers/yup';
import { useQuery } from 'react-query';
import { array, mixed, object, string } from 'yup';

import {
  OptionProps,
  RadioGroupOrientation,
  YBInput,
  YBInputField,
  YBRadioGroupField,
  YBToggleField
} from '../../../../../redesign/components';
import { YBButton } from '../../../../common/forms/fields';
import {
  ConfigureRegionModal,
  CloudVendorRegionField
} from '../configureRegion/ConfigureRegionModal';
import { DeleteRegionModal } from '../../components/DeleteRegionModal';
import { NTPConfigField } from '../../components/NTPConfigField';
import { RegionList } from '../../components/RegionList';
import { YBDropZoneField } from '../../components/YBDropZone/YBDropZoneField';
import {
  KeyPairManagement,
  KEY_PAIR_MANAGEMENT_OPTIONS,
  NTPSetupType,
  ProviderCode,
  VPCSetupType,
  VPCSetupTypeLabel
} from '../../constants';
import { FieldGroup } from '../components/FieldGroup';
import {
  addItem,
  deleteItem,
  editItem,
  generateLowerCaseAlphanumericId,
  readFileAsText
} from '../utils';
import { FormContainer } from '../components/FormContainer';
import { ACCEPTABLE_CHARS } from '../../../../config/constants';
import { FormField } from '../components/FormField';
import { FieldLabel } from '../components/FieldLabel';
import { GCP_REGIONS } from '../../providerRegionsData';
import { YBErrorIndicator, YBLoading } from '../../../../common/indicators';
import { api, hostInfoQueryKey } from '../../../../../redesign/helpers/api';
import { getNtpSetupType, getYBAHost } from '../../utils';
import { YBAHost } from '../../../../../redesign/helpers/constants';
import { RegionOperation } from '../configureRegion/constants';
import { toast } from 'react-toastify';
import { assertUnreachableCase } from '../../../../../utils/errorHandlingUtils';
import { EditProvider } from '../ProviderEditView';
import { VersionWarningBanner } from '../components/VersionWarningBanner';
import { NTP_SERVER_REGEX } from '../constants';

import {
  GCPRegionMutation,
  GCPAvailabilityZoneMutation,
  YBProviderMutation,
  GCPProvider
} from '../../types';

interface GCPProviderEditFormProps {
  editProvider: EditProvider;
  isProviderInUse: boolean;
  providerConfig: GCPProvider;
}

interface GCPProviderEditFormFieldValues {
  dbNodePublicInternetAccess: boolean;
  destVpcId: string;
  editCloudCredentials: boolean;
  editSSHKeypair: boolean;
  gceProject: string;
  googleServiceAccount: File;
  ntpServers: string[];
  ntpSetupType: NTPSetupType;
  providerCredentialType: ProviderCredentialType;
  providerName: string;
  regions: CloudVendorRegionField[];
  sshKeypairManagement: KeyPairManagement;
  sshKeypairName: string;
  sshPort: number;
  sshPrivateKeyContent: File;
  sshUser: string;
  version: number;
  vpcSetupType: VPCSetupType;
  ybFirewallTags: string;
}

const ProviderCredentialType = {
  HOST_INSTANCE_SERVICE_ACCOUNT: 'hostInstanceServiceAccount',
  SPECIFIED_SERVICE_ACCOUNT: 'specifiedServiceAccount'
} as const;
type ProviderCredentialType = typeof ProviderCredentialType[keyof typeof ProviderCredentialType];

const YB_VPC_NAME_BASE = 'yb-gcp-network';

const VALIDATION_SCHEMA = object().shape({
  providerName: string()
    .required('Provider Name is required.')
    .matches(
      ACCEPTABLE_CHARS,
      'Provider name cannot contain special characters other than "-", and "_"'
    ),
  googleServiceAccount: mixed().when(['editCloudCredentials', 'providerCredentialType'], {
    is: (editCloudCredentials, providerCredentialType) =>
      editCloudCredentials &&
      providerCredentialType === ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT,
    then: mixed().required('Service account config is required.')
  }),
  destVpcId: string().when('vpcSetupType', {
    is: (vpcSetupType: VPCSetupType) =>
      ([VPCSetupType.EXISTING, VPCSetupType.NEW] as VPCSetupType[]).includes(vpcSetupType),
    then: string().required('Custom GCE Network is required.')
  }),
  sshKeypairManagement: mixed().when('editSSHKeypair', {
    is: true,
    then: mixed().oneOf(
      [KeyPairManagement.SELF_MANAGED, KeyPairManagement.YBA_MANAGED],
      'SSH Keypair management choice is required.'
    )
  }),
  sshPrivateKeyContent: mixed().when(['editSSHKeypair', 'sshKeypairManagement'], {
    is: (editSSHKeypair, sshKeypairManagement) =>
      editSSHKeypair && sshKeypairManagement === KeyPairManagement.SELF_MANAGED,
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

export const GCPProviderEditForm = ({
  editProvider,
  isProviderInUse,
  providerConfig
}: GCPProviderEditFormProps) => {
  const [isRegionFormModalOpen, setIsRegionFormModalOpen] = useState<boolean>(false);
  const [isDeleteRegionModalOpen, setIsDeleteRegionModalOpen] = useState<boolean>(false);
  const [regionSelection, setRegionSelection] = useState<CloudVendorRegionField>();
  const [regionOperation, setRegionOperation] = useState<RegionOperation>(RegionOperation.ADD);

  const defaultValues = constructDefaultFormValues(providerConfig);
  const formMethods = useForm<GCPProviderEditFormFieldValues>({
    defaultValues: defaultValues,
    resolver: yupResolver(VALIDATION_SCHEMA)
  });

  const hostInfoQuery = useQuery(hostInfoQueryKey.ALL, () => api.fetchHostInfo());

  if (hostInfoQuery.isLoading || hostInfoQuery.isIdle) {
    return <YBLoading />;
  }
  if (hostInfoQuery.isError) {
    return <YBErrorIndicator customErrorMessage="Error fetching host info." />;
  }

  const onFormSubmit: SubmitHandler<GCPProviderEditFormFieldValues> = async (formValues) => {
    if (formValues.ntpSetupType === NTPSetupType.SPECIFIED && !formValues.ntpServers.length) {
      formMethods.setError('ntpServers', {
        type: 'min',
        message: 'Please specify at least one NTP server.'
      });
      return;
    }

    try {
      const providerPayload = await constructProviderPayload(formValues, providerConfig);
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
  const showDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(true);
  };
  const hideDeleteRegionModal = () => {
    setIsDeleteRegionModalOpen(false);
  };
  const hideRegionFormModal = () => {
    setIsRegionFormModalOpen(false);
  };

  const credentialOptions: OptionProps[] = [
    {
      value: ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT,
      label: 'Upload service account config'
    },
    {
      value: ProviderCredentialType.HOST_INSTANCE_SERVICE_ACCOUNT,
      label: `Use service account from this YBA host's instance`,
      disabled: getYBAHost(hostInfoQuery.data) !== YBAHost.GCP
    }
  ];

  const regions = formMethods.watch('regions', defaultValues.regions);
  const setRegions = (regions: CloudVendorRegionField[]) =>
    formMethods.setValue('regions', regions);
  const onRegionFormSubmit = (currentRegion: CloudVendorRegionField) => {
    regionOperation === RegionOperation.ADD
      ? addItem(currentRegion, regions, setRegions)
      : editItem(currentRegion, regions, setRegions);
  };
  const onDeleteRegionSubmit = (currentRegion: CloudVendorRegionField) =>
    deleteItem(currentRegion, regions, setRegions);

  const providerCredentialType = formMethods.watch(
    'providerCredentialType',
    defaultValues.providerCredentialType
  );

  const vpcSetupOptions: OptionProps[] = [
    {
      value: VPCSetupType.EXISTING,
      label: VPCSetupTypeLabel[VPCSetupType.EXISTING]
    },
    {
      value: VPCSetupType.HOST_INSTANCE,
      label: VPCSetupTypeLabel[VPCSetupType.HOST_INSTANCE],
      disabled: providerCredentialType !== ProviderCredentialType.HOST_INSTANCE_SERVICE_ACCOUNT
    },
    {
      value: VPCSetupType.NEW,
      label: VPCSetupTypeLabel[VPCSetupType.NEW],
      disabled: true // Disabling 'Create new VPC' until we're able to fully test our support for this.
    }
  ];

  const currentProviderVersion = formMethods.watch('version', defaultValues.version);
  const vpcSetupType = formMethods.watch('vpcSetupType', defaultValues.vpcSetupType);
  const keyPairManagement = formMethods.watch('sshKeypairManagement');
  const editSSHKeypair = formMethods.watch('editSSHKeypair', defaultValues.editSSHKeypair);
  const editCloudCredentials = formMethods.watch(
    'editCloudCredentials',
    defaultValues.editCloudCredentials
  );
  const isFormDisabled =
    isProviderInUse || formMethods.formState.isValidating || formMethods.formState.isSubmitting;
  return (
    <Box display="flex" justifyContent="center">
      <FormProvider {...formMethods}>
        <FormContainer name="gcpProviderForm" onSubmit={formMethods.handleSubmit(onFormSubmit)}>
          {currentProviderVersion < providerConfig.version && (
            <VersionWarningBanner onReset={onFormReset} />
          )}
          <Typography variant="h3">Manage GCP Provider Configuration</Typography>
          <FormField providerNameField={true}>
            <FieldLabel>Provider Name</FieldLabel>
            <YBInputField
              control={formMethods.control}
              name="providerName"
              disabled={isFormDisabled}
              fullWidth
            />
          </FormField>
          <Box width="100%" display="flex" flexDirection="column" gridGap="32px">
            <FieldGroup heading="Cloud Info">
              <FormField>
                <FieldLabel>Current Service Account Email</FieldLabel>
                <YBInput
                  value={
                    providerConfig.details.cloudInfo.gcp.gceApplicationCredentials.client_email
                  }
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Current GCE Project Name</FieldLabel>
                <YBInput
                  value={providerConfig.details.cloudInfo.gcp.gceProject}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Change Cloud Credentials</FieldLabel>
                <YBToggleField
                  name="editCloudCredentials"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {editCloudCredentials && (
                <>
                  <FormField>
                    <FieldLabel>Credential Type</FieldLabel>
                    <YBRadioGroupField
                      name="providerCredentialType"
                      control={formMethods.control}
                      options={credentialOptions}
                      orientation={RadioGroupOrientation.HORIZONTAL}
                    />
                  </FormField>
                  {providerCredentialType === ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT && (
                    <FormField>
                      <FieldLabel>Service Account</FieldLabel>
                      <YBDropZoneField
                        name="googleServiceAccount"
                        control={formMethods.control}
                        actionButtonText="Upload Google service account JSON"
                        multipleFiles={false}
                        showHelpText={false}
                        disabled={isFormDisabled}
                      />
                    </FormField>
                  )}
                  <FormField>
                    <FieldLabel>GCE Project Name (Optional Override)</FieldLabel>
                    <YBInputField
                      control={formMethods.control}
                      name="gceProject"
                      disabled={isFormDisabled}
                      fullWidth
                    />
                  </FormField>
                </>
              )}
              <FormField>
                <FieldLabel>VPC Setup</FieldLabel>
                <YBRadioGroupField
                  name="vpcSetupType"
                  control={formMethods.control}
                  options={vpcSetupOptions}
                  orientation={RadioGroupOrientation.HORIZONTAL}
                  onRadioChange={(_event, value) => {
                    if (value === VPCSetupType.NEW) {
                      formMethods.setValue(
                        'destVpcId',
                        `${YB_VPC_NAME_BASE}-${generateLowerCaseAlphanumericId()}`
                      );
                    } else {
                      formMethods.setValue('destVpcId', '');
                    }
                  }}
                />
              </FormField>
              {(vpcSetupType === VPCSetupType.EXISTING || vpcSetupType === VPCSetupType.NEW) && (
                <FormField>
                  <FieldLabel>Custom GCE Network Name</FieldLabel>
                  <YBInputField
                    control={formMethods.control}
                    name="destVpcId"
                    disabled={isFormDisabled}
                    fullWidth
                  />
                </FormField>
              )}
            </FieldGroup>
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
                providerCode={ProviderCode.GCP}
                regions={regions}
                setRegionSelection={setRegionSelection}
                showAddRegionFormModal={showAddRegionFormModal}
                showEditRegionFormModal={showEditRegionFormModal}
                showDeleteRegionModal={showDeleteRegionModal}
                disabled={isFormDisabled}
                isError={!!formMethods.formState.errors.regions}
              />
              {formMethods.formState.errors.regions?.message && (
                <FormHelperText error={true}>
                  {formMethods.formState.errors.regions?.message}
                </FormHelperText>
              )}
            </FieldGroup>
            <FieldGroup heading="SSH Key Pairs">
              <FormField>
                <FieldLabel>SSH User</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshUser"
                  disabled={isFormDisabled}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>SSH Port</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshPort"
                  type="number"
                  inputProps={{ min: 0, max: 65535 }}
                  disabled={isFormDisabled}
                  fullWidth
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
                    <FieldLabel>Key Pair Management</FieldLabel>
                    <YBRadioGroupField
                      name="sshKeypairManagement"
                      control={formMethods.control}
                      options={KEY_PAIR_MANAGEMENT_OPTIONS}
                      orientation={RadioGroupOrientation.HORIZONTAL}
                    />
                  </FormField>
                  {keyPairManagement === KeyPairManagement.SELF_MANAGED && (
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
                </>
              )}
            </FieldGroup>
            <FieldGroup heading="Advanced">
              <FormField>
                <FieldLabel>Firewall Tags</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="ybFirewallTags"
                  placeholder="my-firewall-tag-1,my-firewall-tag-2"
                  disabled={isFormDisabled}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>DB Nodes have public internet access?</FieldLabel>
                <YBToggleField
                  name="dbNodePublicInternetAccess"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>NTP Setup</FieldLabel>
                <NTPConfigField
                  isDisabled={formMethods.formState.isSubmitting}
                  providerCode={ProviderCode.GCP}
                />
              </FormField>
            </FieldGroup>
            {(formMethods.formState.isValidating || formMethods.formState.isSubmitting) && (
              <Box display="flex" gridGap="5px" marginLeft="auto">
                <CircularProgress size={16} color="primary" thickness={5} />
              </Box>
            )}
          </Box>
          <Box marginTop="16px">
            <YBButton
              btnText="Apply Changes"
              btnClass="btn btn-default save-btn"
              btnType="submit"
              disabled={isFormDisabled}
              data-testid="GCPProviderEditForm-SubmitButton"
            />
            <YBButton
              btnText="Clear Changes"
              btnClass="btn btn-default"
              onClick={onFormReset}
              disabled={isFormDisabled}
              data-testid="GCPProviderEditForm-BackButton"
            />
          </Box>
        </FormContainer>
      </FormProvider>
      {isRegionFormModalOpen && (
        <ConfigureRegionModal
          configuredRegions={regions}
          onClose={hideRegionFormModal}
          onRegionSubmit={onRegionFormSubmit}
          open={isRegionFormModalOpen}
          providerCode={ProviderCode.GCP}
          regionOperation={regionOperation}
          regionSelection={regionSelection}
          vpcSetupType={vpcSetupType}
        />
      )}
      <DeleteRegionModal
        region={regionSelection}
        onClose={hideDeleteRegionModal}
        open={isDeleteRegionModalOpen}
        deleteRegion={onDeleteRegionSubmit}
      />
    </Box>
  );
};

const constructDefaultFormValues = (
  providerConfig: GCPProvider
): Partial<GCPProviderEditFormFieldValues> => ({
  dbNodePublicInternetAccess: !providerConfig.details.airGapInstall,
  destVpcId: providerConfig.details.cloudInfo.gcp.destVpcId,
  editCloudCredentials: false,
  editSSHKeypair: false,
  ntpServers: providerConfig.details.ntpServers,
  ntpSetupType: getNtpSetupType(providerConfig),
  providerName: providerConfig.name,
  providerCredentialType: providerConfig.details.cloudInfo.gcp.useHostCredentials
    ? ProviderCredentialType.HOST_INSTANCE_SERVICE_ACCOUNT
    : ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT,
  regions: providerConfig.regions.map((region) => ({
    fieldId: generateLowerCaseAlphanumericId(),
    code: region.code,
    ybImage: region.details.cloudInfo.gcp.ybImage ?? '',
    sharedSubnet: region.zones?.[0]?.subnet ?? '',
    zones: GCP_REGIONS[region.code]?.zones.map<GCPAvailabilityZoneMutation>(
      (zoneSuffix: string) => ({
        code: `${region.code}${zoneSuffix}`,
        name: `${region.code}${zoneSuffix}`,
        subnet: region.zones?.[0]?.subnet ?? ''
      })
    )
  })),
  sshKeypairManagement: providerConfig.allAccessKeys?.[0]?.keyInfo.managementState,
  sshPort: providerConfig.details.sshPort,
  sshUser: providerConfig.details.sshUser,
  version: providerConfig.version,
  vpcSetupType: providerConfig.details.cloudInfo.gcp.vpcType,
  ybFirewallTags: providerConfig.details.cloudInfo.gcp.ybFirewallTags
});

const constructProviderPayload = async (
  formValues: GCPProviderEditFormFieldValues,
  providerConfig: GCPProvider
): Promise<YBProviderMutation> => {
  let googleServiceAccount = null;
  if (
    formValues.providerCredentialType === ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT &&
    formValues.googleServiceAccount
  ) {
    try {
      const googleServiceAccountText = await readFileAsText(formValues.googleServiceAccount);
      if (googleServiceAccountText) {
        googleServiceAccount = JSON.parse(googleServiceAccountText);
      }
    } catch (error) {
      throw new Error(
        `An error occurred while processing the Google service account file: ${error}`
      );
    }
  }

  let sshPrivateKeyContent = '';
  try {
    sshPrivateKeyContent =
      formValues.sshKeypairManagement === KeyPairManagement.SELF_MANAGED &&
      formValues.sshPrivateKeyContent
        ? (await readFileAsText(formValues.sshPrivateKeyContent)) ?? ''
        : '';
  } catch (error) {
    throw new Error(`An error occurred while processing the SSH private key file: ${error}`);
  }

  // Note: Backend expects `useHostVPC` to be true for both host instance VPC and specified VPC for
  //       backwards compatability reasons.
  const vpcConfig =
    formValues.vpcSetupType === VPCSetupType.HOST_INSTANCE
      ? {
          useHostVPC: true
        }
      : formValues.vpcSetupType === VPCSetupType.EXISTING
      ? {
          useHostVPC: true, // Must be sent as true for backwards compatability.
          destVpcId: formValues.destVpcId
        }
      : formValues.vpcSetupType === VPCSetupType.NEW
      ? {
          useHostVPC: false,
          destVpcId: formValues.destVpcId
        }
      : assertUnreachableCase(formValues.vpcSetupType);

  const gcpCredentials =
    formValues.providerCredentialType === ProviderCredentialType.HOST_INSTANCE_SERVICE_ACCOUNT
      ? {
          useHostCredentials: true
        }
      : formValues.providerCredentialType === ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT
      ? {
          gceApplicationCredentials: googleServiceAccount,
          gceProject: formValues.gceProject ?? googleServiceAccount?.project_id ?? '',
          useHostCredentials: false
        }
      : assertUnreachableCase(formValues.providerCredentialType);

  return {
    code: ProviderCode.GCP,
    name: formValues.providerName,
    ...(formValues.editSSHKeypair &&
      formValues.sshKeypairManagement === KeyPairManagement.SELF_MANAGED && {
        ...(formValues.sshKeypairName && { keyPairName: formValues.sshKeypairName }),
        ...(formValues.sshPrivateKeyContent && {
          sshPrivateKeyContent: sshPrivateKeyContent
        })
      }),
    details: {
      airGapInstall: !formValues.dbNodePublicInternetAccess,
      cloudInfo: {
        [ProviderCode.GCP]: {
          ...providerConfig.details.cloudInfo.gcp,
          ...vpcConfig,
          ...(formValues.editCloudCredentials && { ...gcpCredentials }),
          ybFirewallTags: formValues.ybFirewallTags
        }
      },
      ntpServers: formValues.ntpServers,
      setUpChrony: formValues.ntpSetupType !== NTPSetupType.NO_NTP,
      sshPort: formValues.sshPort,
      sshUser: formValues.sshUser
    },
    regions: formValues.regions.map<GCPRegionMutation>((regionFormValues) => ({
      code: regionFormValues.code,
      details: {
        cloudInfo: {
          [ProviderCode.GCP]: {
            ybImage: regionFormValues.ybImage
          }
        }
      },
      zones: GCP_REGIONS[regionFormValues.code]?.zones.map<GCPAvailabilityZoneMutation>(
        (zoneSuffix: string) => ({
          code: `${regionFormValues.code}${zoneSuffix}`,
          name: `${regionFormValues.code}${zoneSuffix}`,
          subnet: regionFormValues.sharedSubnet ?? ''
        })
      )
    })),
    version: formValues.version
  };
};
