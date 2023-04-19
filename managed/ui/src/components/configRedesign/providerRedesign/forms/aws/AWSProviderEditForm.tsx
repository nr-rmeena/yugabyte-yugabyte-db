/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React, { useState } from 'react';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import { Box, CircularProgress, FormHelperText, Typography } from '@material-ui/core';
import { useQuery } from 'react-query';
import { useSelector } from 'react-redux';
import { AxiosError } from 'axios';
import { yupResolver } from '@hookform/resolvers/yup';
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
import { FieldGroup } from '../components/FieldGroup';
import {
  CloudVendorRegionField,
  ConfigureRegionModal
} from '../configureRegion/ConfigureRegionModal';
import {
  KeyPairManagement,
  KEY_PAIR_MANAGEMENT_OPTIONS,
  NTPSetupType,
  ProviderCode,
  VPCSetupType,
  YBImageType
} from '../../constants';
import { EditProvider } from '../ProviderEditView';
import { FieldLabel } from '../components/FieldLabel';
import { FormContainer } from '../components/FormContainer';
import { FormField } from '../components/FormField';
import { NTPConfigField } from '../../components/NTPConfigField';
import { RegionList } from '../../components/RegionList';
import { RegionOperation } from '../configureRegion/constants';
import {
  findExistingRegion,
  findExistingZone,
  getDeletedRegions,
  getDeletedZones,
  getNtpSetupType,
  getYBAHost
} from '../../utils';
import {
  addItem,
  deleteItem,
  editItem,
  generateLowerCaseAlphanumericId,
  readFileAsText
} from '../utils';
import { YBButton as YBRedesignedButton } from '../../../../../redesign/components';
import { QuickValidationErrorKeys } from './AWSProviderCreateForm';
import { getInvalidFields, useValidationStyles } from './utils';
import { DeleteRegionModal } from '../../components/DeleteRegionModal';
import { api, hostInfoQueryKey } from '../../../../../redesign/helpers/api';
import { YBErrorIndicator, YBLoading } from '../../../../common/indicators';
import { YBBanner, YBBannerVariant } from '../../../../common/descriptors';
import { YBAHost } from '../../../../../redesign/helpers/constants';
import { isAxiosError, isYBPBeanValidationError } from '../../../../../utils/errorHandlingUtils';
import { YBPError, YBPStructuredError } from '../../../../../redesign/helpers/dtos';
import { AWSProviderCredentialType, VPC_SETUP_OPTIONS } from './constants';
import { YBDropZoneField } from '../../components/YBDropZone/YBDropZoneField';
import { VersionWarningBanner } from '../components/VersionWarningBanner';
import { NTP_SERVER_REGEX } from '../constants';
import { ACCEPTABLE_CHARS } from '../../../../config/constants';

import {
  AWSAvailabilityZone,
  AWSAvailabilityZoneMutation,
  AWSProvider,
  AWSRegion,
  AWSRegionMutation,
  YBProviderMutation
} from '../../types';
import { toast } from 'react-toastify';

interface AWSProviderEditFormProps {
  editProvider: EditProvider;
  isProviderInUse: boolean;
  providerConfig: AWSProvider;
}

export interface AWSProviderEditFormFieldValues {
  accessKeyId: string;
  dbNodePublicInternetAccess: boolean;
  editAccessKey: boolean;
  editSSHKeypair: boolean;
  enableHostedZone: boolean;
  hostedZoneId: string;
  ntpServers: string[];
  ntpSetupType: NTPSetupType;
  providerCredentialType: AWSProviderCredentialType;
  providerName: string;
  regions: CloudVendorRegionField[];
  secretAccessKey: string;
  sshKeypairManagement: KeyPairManagement;
  sshKeypairName: string;
  sshPort: number;
  sshPrivateKeyContent: File;
  sshUser: string;
  vpcSetupType: VPCSetupType;
  ybImageType: YBImageType;
  version: number;
}

const VALIDATION_SCHEMA = object().shape({
  providerName: string()
    .required('Provider Name is required.')
    .matches(
      ACCEPTABLE_CHARS,
      'Provider name cannot contain special characters other than "-", and "_"'
    ),
  // Specified provider credential types
  accessKeyId: string().when(['editAccessKey', 'providerCredentialType'], {
    is: (editAccessKey, providerCredentialType) =>
      editAccessKey && providerCredentialType === AWSProviderCredentialType.ACCESS_KEY,
    then: string().required('Access key id is required.')
  }),
  secretAccessKey: string().when(['editAccessKey', 'providerCredentialType'], {
    is: (editAccessKey, providerCredentialType) =>
      editAccessKey && providerCredentialType === AWSProviderCredentialType.ACCESS_KEY,
    then: string().required('Secret access key id is required.')
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
  hostedZoneId: string().when('enableHostedZone', {
    is: true,
    then: string().required('Route 53 zone id is required.')
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

const FORM_NAME = 'AWSProviderEditForm';

export const AWSProviderEditForm = ({
  editProvider,
  isProviderInUse,
  providerConfig
}: AWSProviderEditFormProps) => {
  const [isRegionFormModalOpen, setIsRegionFormModalOpen] = useState<boolean>(false);
  const [isDeleteRegionModalOpen, setIsDeleteRegionModalOpen] = useState<boolean>(false);
  const [regionSelection, setRegionSelection] = useState<CloudVendorRegionField>();
  const [regionOperation, setRegionOperation] = useState<RegionOperation>(RegionOperation.ADD);
  const featureFlags = useSelector((state: any) => state.featureFlags);
  const [
    quickValidationErrors,
    setQuickValidationErrors
  ] = useState<QuickValidationErrorKeys | null>(null);
  const validationClasses = useValidationStyles();
  const defaultValues = constructDefaultFormValues(providerConfig);
  const formMethods = useForm<AWSProviderEditFormFieldValues>({
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

  const handleFormSubmitServerError = (
    error: Error | AxiosError<YBPStructuredError | YBPError>
  ) => {
    if (
      featureFlags.test.enableAWSProviderValidation &&
      isAxiosError<YBPStructuredError | YBPError>(error) &&
      isYBPBeanValidationError(error) &&
      error.response?.data.error
    ) {
      // Handle YBBeanValidationError
      const { errorSource, ...validationErrors } = error.response?.data.error;
      const invalidFields = validationErrors ? getInvalidFields(validationErrors) : [];
      if (invalidFields) {
        setQuickValidationErrors(validationErrors ?? null);
      }
      invalidFields.forEach((fieldName) =>
        formMethods.setError(fieldName, {
          type: 'server',
          message:
            'Validation Error. See the field validation failure at the bottom of the page for more details.'
        })
      );
    }
  };

  const onFormReset = () => {
    formMethods.reset(defaultValues);
  };
  const clearErrors = () => {
    formMethods.clearErrors();
    setQuickValidationErrors(null);
  };
  const onFormSubmit = async (
    formValues: AWSProviderEditFormFieldValues,
    shouldValidate = true
  ) => {
    clearErrors();

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
        await editProvider(providerPayload, {
          shouldValidate: shouldValidate,
          mutateOptions: { onError: handleFormSubmitServerError }
        });
      } catch (_) {
        // Handled by onError callback
      }
    } catch (error: any) {
      toast.error(error.message ?? error);
    }
  };
  const onFormValidateAndSubmit: SubmitHandler<AWSProviderEditFormFieldValues> = async (
    formValues
  ) => onFormSubmit(formValues, !!featureFlags.test.enableAWSProviderValidation);
  const onFormForceSubmit: SubmitHandler<AWSProviderEditFormFieldValues> = async (formValues) =>
    onFormSubmit(formValues, false);

  const showAddRegionFormModal = () => {
    setRegionSelection(undefined);
    setRegionOperation(RegionOperation.ADD);
    setIsRegionFormModalOpen(true);
  };
  const showEditRegionFormModal = () => {
    setRegionOperation(RegionOperation.EDIT_NEW);
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
  const skipValidationAndSubmit = () => {
    onFormForceSubmit(formMethods.getValues());
  };

  const regions = formMethods.watch('regions');
  const setRegions = (regions: CloudVendorRegionField[]) =>
    formMethods.setValue('regions', regions, { shouldValidate: true });
  const onRegionFormSubmit = (currentRegion: CloudVendorRegionField) => {
    regionOperation === RegionOperation.ADD
      ? addItem(currentRegion, regions, setRegions)
      : editItem(currentRegion, regions, setRegions);
  };
  const onDeleteRegionSubmit = (currentRegion: CloudVendorRegionField) =>
    deleteItem(currentRegion, regions, setRegions);

  const credentialOptions: OptionProps[] = [
    {
      value: AWSProviderCredentialType.ACCESS_KEY,
      label: 'Specify Access ID and Secret Key'
    },
    {
      value: AWSProviderCredentialType.HOST_INSTANCE_IAM_ROLE,
      label: `Use IAM Role from this YBA host's instance`,
      disabled: getYBAHost(hostInfoQuery.data) !== YBAHost.AWS
    }
  ];
  const currentProviderVersion = formMethods.watch('version', defaultValues.version);
  const enableHostedZone = formMethods.watch('enableHostedZone');
  const keyPairManagement = formMethods.watch('sshKeypairManagement');
  const editAccessKey = formMethods.watch('editAccessKey', defaultValues.editAccessKey);
  const editSSHKeypair = formMethods.watch('editSSHKeypair', defaultValues.editSSHKeypair);
  const providerCredentialType = formMethods.watch('providerCredentialType');
  const vpcSetupType = formMethods.watch('vpcSetupType', defaultValues.vpcSetupType);
  const ybImageType = formMethods.watch('ybImageType');
  const isFormDisabled =
    isProviderInUse || formMethods.formState.isValidating || formMethods.formState.isSubmitting;
  return (
    <Box display="flex" justifyContent="center">
      <FormProvider {...formMethods}>
        <FormContainer
          name="awsProviderForm"
          onSubmit={formMethods.handleSubmit(onFormValidateAndSubmit)}
        >
          {currentProviderVersion < providerConfig.version && (
            <VersionWarningBanner onReset={onFormReset} dataTestIdPrefix={FORM_NAME} />
          )}
          <Typography variant="h3">Manage AWS Provider Configuration</Typography>
          <FormField providerNameField={true}>
            <FieldLabel>Provider Name</FieldLabel>
            <YBInputField
              control={formMethods.control}
              name="providerName"
              required={true}
              disabled={isFormDisabled}
              fullWidth
            />
          </FormField>
          <Box width="100%" display="flex" flexDirection="column" gridGap="32px">
            <FieldGroup
              heading="Cloud Info"
              infoTitle="Cloud Info"
              infoContent="Enter your cloud credentials and specify how Yugabyte should leverage cloud services."
            >
              <FormField>
                <FieldLabel
                  infoTitle="Credential Type"
                  infoContent="For public cloud Providers YBA creates compute instances, and therefore requires sufficient permissions to do so."
                >
                  Credential Type
                </FieldLabel>
                <YBRadioGroupField
                  name="providerCredentialType"
                  control={formMethods.control}
                  options={credentialOptions}
                  orientation={RadioGroupOrientation.HORIZONTAL}
                  isDisabled={isFormDisabled}
                />
              </FormField>
              {providerCredentialType === AWSProviderCredentialType.ACCESS_KEY && (
                <>
                  <FormField>
                    <FieldLabel>Current Access Key ID</FieldLabel>
                    <YBInput
                      value={providerConfig.details.cloudInfo.aws.awsAccessKeyID}
                      disabled={true}
                      fullWidth
                    />
                  </FormField>
                  <FormField>
                    <FieldLabel>Current Secret Access Key</FieldLabel>
                    <YBInput
                      value={providerConfig.details.cloudInfo.aws.awsAccessKeySecret}
                      disabled={true}
                      fullWidth
                    />
                  </FormField>
                  <FormField>
                    <FieldLabel>Change Access Key</FieldLabel>
                    <YBToggleField
                      name="editAccessKey"
                      control={formMethods.control}
                      disabled={isFormDisabled}
                    />
                  </FormField>
                  {editAccessKey && (
                    <>
                      <FormField>
                        <FieldLabel>Access Key ID</FieldLabel>
                        <YBInputField
                          control={formMethods.control}
                          name="accessKeyId"
                          disabled={isFormDisabled}
                          fullWidth
                        />
                      </FormField>
                      <FormField>
                        <FieldLabel>Secret Access Key</FieldLabel>
                        <YBInputField
                          control={formMethods.control}
                          name="secretAccessKey"
                          disabled={isFormDisabled}
                          fullWidth
                        />
                      </FormField>
                    </>
                  )}
                </>
              )}
              <FormField>
                <FieldLabel>Use AWS Route 53 DNS Server</FieldLabel>
                <YBToggleField
                  name="enableHostedZone"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              {enableHostedZone && (
                <FormField>
                  <FieldLabel>Hosted Zone ID</FieldLabel>
                  <YBInputField
                    control={formMethods.control}
                    name="hostedZoneId"
                    disabled={isFormDisabled}
                    fullWidth
                  />
                </FormField>
              )}
            </FieldGroup>
            <FieldGroup
              heading="Regions"
              infoTitle="Regions"
              infoContent="Which regions would you like to allow DB nodes to be deployed into?"
              headerAccessories={
                regions.length > 0 ? (
                  <YBButton
                    btnIcon="fa fa-plus"
                    btnText="Add Region"
                    btnClass="btn btn-default"
                    btnType="button"
                    onClick={showAddRegionFormModal}
                    disabled={isFormDisabled}
                    data-testid={`${FORM_NAME}-AddRegionButton`}
                  />
                ) : null
              }
            >
              <FormField>
                <FieldLabel>VPC Setup</FieldLabel>
                <YBRadioGroupField
                  name="vpcSetupType"
                  control={formMethods.control}
                  options={VPC_SETUP_OPTIONS}
                  orientation={RadioGroupOrientation.HORIZONTAL}
                  isDisabled={isFormDisabled}
                />
              </FormField>
              <RegionList
                providerCode={ProviderCode.AWS}
                regions={regions}
                setRegionSelection={setRegionSelection}
                showAddRegionFormModal={showAddRegionFormModal}
                showEditRegionFormModal={showEditRegionFormModal}
                showDeleteRegionModal={showDeleteRegionModal}
                disabled={isFormDisabled}
                isError={!!formMethods.formState.errors.regions}
              />
              {!!formMethods.formState.errors.regions?.message && (
                <FormHelperText error={true}>
                  {formMethods.formState.errors.regions?.message}
                </FormHelperText>
              )}
            </FieldGroup>
            <FieldGroup
              heading="SSH Key Pairs"
              infoTitle="SSH Key Pairs"
              infoContent="YBA requires SSH access to DB nodes. For public clouds, YBA provisions the VM instances as part of the DB node provisioning. The OS images come with a preprovisioned user."
            >
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
                  value={providerConfig.allAccessKeys[0]?.keyInfo?.keyPairName}
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>Current SSH Private Key</FieldLabel>
                <YBInput
                  value={providerConfig.allAccessKeys[0]?.keyInfo?.privateKey}
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
                      isDisabled={isFormDisabled}
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
                <FieldLabel
                  infoTitle="DB Nodes have public internet access?"
                  infoContent="If yes, YBA will install some software packages on the DB nodes by downloading from the public internet. If not, all installation of software on the nodes will download from only this YBA instance."
                >
                  DB Nodes have public internet access?
                </FieldLabel>
                <YBToggleField
                  name="dbNodePublicInternetAccess"
                  control={formMethods.control}
                  disabled={isFormDisabled}
                />
              </FormField>
              <FormField>
                <FieldLabel>NTP Setup</FieldLabel>
                <NTPConfigField isDisabled={isFormDisabled} providerCode={ProviderCode.AWS} />
              </FormField>
            </FieldGroup>
            {!!featureFlags.test.enableAWSProviderValidation && !!quickValidationErrors && (
              <YBBanner variant={YBBannerVariant.DANGER}>
                <Typography variant="body1">Fields failed validation:</Typography>
                <ul className={validationClasses.errorList}>
                  {Object.entries(quickValidationErrors).map(([keyString, errors]) => {
                    return (
                      <li key={keyString}>
                        {keyString.replace(/^(data\.)/, '')}
                        <ul>
                          {errors.map((error, index) => (
                            <li key={index}>{error}</li>
                          ))}
                        </ul>
                      </li>
                    );
                  })}
                </ul>
                <YBRedesignedButton
                  variant="secondary"
                  onClick={skipValidationAndSubmit}
                  data-testid={`${FORM_NAME}-SkipValidationButton`}
                >
                  Ignore and save provider configuration anyway
                </YBRedesignedButton>
              </YBBanner>
            )}
            {(formMethods.formState.isValidating || formMethods.formState.isSubmitting) && (
              <Box display="flex" gridGap="5px" marginLeft="auto">
                <CircularProgress size={16} color="primary" thickness={5} />
                {!!featureFlags.test.enableAWSProviderValidation && (
                  <Typography variant="body2" color="primary">
                    Validating provider configuration fields... usually take 5-30s to complete.
                  </Typography>
                )}
              </Box>
            )}
          </Box>
          <Box marginTop="16px">
            <YBButton
              btnText={
                featureFlags.test.enableAWSProviderValidation
                  ? 'Validate and Apply Changes'
                  : 'Apply Changes'
              }
              btnClass="btn btn-default save-btn"
              btnType="submit"
              disabled={isFormDisabled}
              data-testid={`${FORM_NAME}-SubmitButton`}
            />
            <YBButton
              btnText="Clear Changes"
              btnClass="btn btn-default"
              onClick={onFormReset}
              disabled={isFormDisabled}
              data-testid={`${FORM_NAME}-ClearButton`}
            />
          </Box>
        </FormContainer>
      </FormProvider>
      {/* Modals */}
      {isRegionFormModalOpen && (
        <ConfigureRegionModal
          configuredRegions={regions}
          onClose={hideRegionFormModal}
          onRegionSubmit={onRegionFormSubmit}
          open={isRegionFormModalOpen}
          providerCode={ProviderCode.AWS}
          regionOperation={regionOperation}
          regionSelection={regionSelection}
          vpcSetupType={vpcSetupType}
          ybImageType={ybImageType}
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
  providerConfig: AWSProvider
): Partial<AWSProviderEditFormFieldValues> => ({
  dbNodePublicInternetAccess: !providerConfig.details.airGapInstall,
  editAccessKey: false,
  editSSHKeypair: false,
  enableHostedZone: !!providerConfig.details.cloudInfo.aws.awsHostedZoneId,
  hostedZoneId: providerConfig.details.cloudInfo.aws.awsHostedZoneId,
  ntpServers: providerConfig.details.ntpServers,
  ntpSetupType: getNtpSetupType(providerConfig),
  providerName: providerConfig.name,
  providerCredentialType: providerConfig.details.cloudInfo.aws.awsAccessKeySecret
    ? AWSProviderCredentialType.ACCESS_KEY
    : AWSProviderCredentialType.HOST_INSTANCE_IAM_ROLE,
  regions: providerConfig.regions.map((region) => ({
    fieldId: generateLowerCaseAlphanumericId(),
    code: region.code,
    vnet: region.details.cloudInfo.aws.vnet,
    securityGroupId: region.details.cloudInfo.aws.securityGroupId,
    ybImage: region.details.cloudInfo.aws.ybImage ?? '',
    zones: region.zones
  })),
  sshKeypairManagement: providerConfig.allAccessKeys?.[0]?.keyInfo.managementState,
  sshPort: providerConfig.details.sshPort ?? '',
  sshUser: providerConfig.details.sshUser ?? '',
  version: providerConfig.version,
  vpcSetupType: providerConfig.details.cloudInfo.aws.vpcType
});

const constructProviderPayload = async (
  formValues: AWSProviderEditFormFieldValues,
  providerConfig: AWSProvider
): Promise<YBProviderMutation> => {
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

  return {
    code: ProviderCode.AWS,
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
        [ProviderCode.AWS]: {
          ...(formValues.providerCredentialType === AWSProviderCredentialType.ACCESS_KEY && {
            awsAccessKeyID: formValues.editAccessKey
              ? formValues.accessKeyId
              : providerConfig.details.cloudInfo.aws.awsAccessKeyID,
            awsAccessKeySecret: formValues.editAccessKey
              ? formValues.secretAccessKey
              : providerConfig.details.cloudInfo.aws.awsAccessKeySecret
          }),
          ...(formValues.enableHostedZone && { awsHostedZoneId: formValues.hostedZoneId })
        }
      },
      ntpServers: formValues.ntpServers,
      setUpChrony: formValues.ntpSetupType !== NTPSetupType.NO_NTP,
      sshPort: formValues.sshPort,
      sshUser: formValues.sshUser
    },
    regions: [
      ...formValues.regions.map<AWSRegionMutation>((regionFormValues) => {
        const existingRegion = findExistingRegion<AWSProvider, AWSRegion>(
          providerConfig,
          regionFormValues.code
        );
        return {
          ...(existingRegion && {
            active: existingRegion.active,
            uuid: existingRegion.uuid
          }),
          code: regionFormValues.code,
          details: {
            cloudInfo: {
              [ProviderCode.AWS]: {
                ...(formValues.ybImageType === YBImageType.CUSTOM_AMI
                  ? {
                      ybImage: regionFormValues.ybImage
                    }
                  : { arch: formValues.ybImageType }),
                securityGroupId: regionFormValues.securityGroupId,
                vnet: regionFormValues.vnet
              }
            }
          },
          zones: [
            ...regionFormValues.zones.map<AWSAvailabilityZoneMutation>((azFormValues) => {
              const existingZone = findExistingZone<AWSRegion, AWSAvailabilityZone>(
                existingRegion,
                azFormValues.code
              );
              return {
                ...(existingZone && {
                  active: existingZone.active,
                  uuid: existingZone.uuid
                }),
                code: azFormValues.code,
                name: azFormValues.code,
                subnet: azFormValues.subnet
              };
            }),
            ...getDeletedZones(existingRegion?.zones, regionFormValues.zones)
          ] as AWSAvailabilityZoneMutation[]
        };
      }),
      ...getDeletedRegions(providerConfig.regions, formValues.regions)
    ] as AWSRegionMutation[],
    version: formValues.version
  };
};
