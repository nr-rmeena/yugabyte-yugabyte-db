import React, { useState } from 'react';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import { Box, Typography } from '@material-ui/core';

import {
  OptionProps,
  RadioGroupOrientation,
  YBInputField,
  YBRadioGroupField,
  YBToggleField
} from '../../../../../redesign/components';
import { YBButton } from '../../../../common/forms/fields';
import { CloudVendorRegionField } from '../configureRegion/ConfigureRegionModal';
import { NTPConfigField } from '../../components/NTPConfigField';
import { RegionList } from '../../components/RegionList';
import { NTPSetupType, ProviderCode, VPCSetupType, VPCSetupTypeLabel } from '../../constants';
import { FieldGroup } from '../components/FieldGroup';
import { FormContainer } from '../components/FormContainer';
import { FormField } from '../components/FormField';
import { FieldLabel } from '../components/FieldLabel';
import { getNtpSetupType } from '../../utils';
import { RegionOperation } from '../configureRegion/constants';

import { YBProvider } from '../../types';

interface GCPProviderEditFormProps {
  providerConfig: YBProvider;
}

interface GCPProviderEditFormFieldValues {
  dbNodePublicInternetAccess: boolean;
  customGceNetwork: string;
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
  vpcSetupType: VPCSetupType;
  ybFirewallTags: string;
}

const ProviderCredentialType = {
  INSTANCE_SERVICE_ACCOUNT: 'instanceServiceAccount',
  SPECIFIED_SERVICE_ACCOUNT: 'specifiedServiceAccount'
} as const;
type ProviderCredentialType = typeof ProviderCredentialType[keyof typeof ProviderCredentialType];

const KeyPairManagement = {
  YBA_MANAGED: 'ybaManaged',
  CUSTOM_KEY_PAIR: 'customKeyPair'
} as const;
type KeyPairManagement = typeof KeyPairManagement[keyof typeof KeyPairManagement];

const VPC_SETUP_OPTIONS: OptionProps[] = [
  {
    value: VPCSetupType.EXISTING,
    label: VPCSetupTypeLabel[VPCSetupType.EXISTING]
  },
  {
    value: VPCSetupType.HOST_INSTANCE,
    label: VPCSetupTypeLabel[VPCSetupType.HOST_INSTANCE]
  },
  {
    value: VPCSetupType.NEW,
    label: VPCSetupTypeLabel[VPCSetupType.NEW],
    disabled: true
  }
];

export const GCPProviderEditForm = ({ providerConfig }: GCPProviderEditFormProps) => {
  const [, setIsRegionFormModalOpen] = useState<boolean>(false);
  const [, setIsDeleteRegionModalOpen] = useState<boolean>(false);
  const [, setRegionSelection] = useState<CloudVendorRegionField>();
  const [, setRegionOperation] = useState<RegionOperation>(RegionOperation.ADD);

  const defaultValues = {
    providerName: providerConfig.name,
    sshKeypairName: providerConfig.allAccessKeys?.[0]?.keyInfo.keyPairName,
    dbNodePublicInternetAccess: !providerConfig.details.airGapInstall,
    ntpSetupType: getNtpSetupType(providerConfig),
    regions: providerConfig.regions,
    ...(providerConfig.code === ProviderCode.GCP && {
      sshUser: providerConfig.details.sshUser,
      sshPort: providerConfig.details.sshPort,
      ntpServers: providerConfig.details.ntpServers,
      vpcSetupType: providerConfig.details.cloudInfo.gcp.useHostVPC
        ? VPCSetupType.HOST_INSTANCE
        : VPCSetupType.EXISTING,
      customGceNetwork: providerConfig.details.cloudInfo.gcp.customGceNetwork,
      gceProject: providerConfig.details.cloudInfo.gcp.gceProject,
      providerCredentialType: providerConfig.details.cloudInfo.gcp.useHostCredentials
        ? ProviderCredentialType.INSTANCE_SERVICE_ACCOUNT
        : ProviderCredentialType.SPECIFIED_SERVICE_ACCOUNT,
      ybFirewallTags: providerConfig.details.cloudInfo.gcp.ybFirewallTags
    })
  };
  const formMethods = useForm<GCPProviderEditFormFieldValues>({
    defaultValues: defaultValues
  });

  const onFormSubmit: SubmitHandler<GCPProviderEditFormFieldValues> = (formValues) => {};

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

  const regions = formMethods.watch('regions');

  const vpcSetupType = formMethods.watch('vpcSetupType', defaultValues.vpcSetupType);
  return (
    <Box display="flex" justifyContent="center">
      <FormProvider {...formMethods}>
        <FormContainer name="gcpProviderForm" onSubmit={formMethods.handleSubmit(onFormSubmit)}>
          <Typography variant="h3">GCP Provider Configuration</Typography>
          <FormField providerNameField={true}>
            <FieldLabel>Provider Name</FieldLabel>
            <YBInputField
              control={formMethods.control}
              name="providerName"
              required={true}
              disabled={true}
              fullWidth
            />
          </FormField>
          <Box width="100%" display="flex" flexDirection="column" gridGap="32px">
            <FieldGroup heading="Cloud Info">
              <FormField>
                <FieldLabel>GCE Project Name</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="gceProject"
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>VPC Setup</FieldLabel>
                <YBRadioGroupField
                  name="vpcSetupType"
                  control={formMethods.control}
                  options={VPC_SETUP_OPTIONS}
                  orientation={RadioGroupOrientation.HORIZONTAL}
                />
              </FormField>
              {(vpcSetupType === VPCSetupType.EXISTING || vpcSetupType === VPCSetupType.NEW) && (
                <FormField>
                  <FieldLabel>Custom GCE Network Name</FieldLabel>
                  <YBInputField
                    control={formMethods.control}
                    name="customGceNetwork"
                    fullWidth
                    disabled={true}
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
                  disabled={formMethods.formState.isSubmitting}
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
                disabled={formMethods.formState.isSubmitting}
              />
            </FieldGroup>
            <FieldGroup heading="SSH Key Pairs">
              <FormField>
                <FieldLabel>SSH User</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshUser"
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>SSH Port</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshPort"
                  type="number"
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>SSH Keypair Name</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="sshKeypairName"
                  disabled={true}
                  fullWidth
                />
              </FormField>
            </FieldGroup>
            <FieldGroup heading="Advanced">
              <FormField>
                <FieldLabel>Firewall Tags</FieldLabel>
                <YBInputField
                  control={formMethods.control}
                  name="ybFirewallTags"
                  disabled={true}
                  fullWidth
                />
              </FormField>
              <FormField>
                <FieldLabel>DB Nodes have public internet access?</FieldLabel>
                <YBToggleField
                  name="dbNodePublicInternetAccess"
                  control={formMethods.control}
                  disabled={true}
                />
              </FormField>
              <FormField>
                <FieldLabel>NTP Setup</FieldLabel>
                <NTPConfigField providerCode={ProviderCode.GCP} />
              </FormField>
            </FieldGroup>
          </Box>
        </FormContainer>
      </FormProvider>
    </Box>
  );
};
