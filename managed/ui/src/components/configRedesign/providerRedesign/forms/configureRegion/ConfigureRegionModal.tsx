/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import React from 'react';
import { FormHelperText, makeStyles } from '@material-ui/core';
import { FormProvider, SubmitHandler, useForm } from 'react-hook-form';
import clsx from 'clsx';
import { v4 as uuidv4 } from 'uuid';
import { array, object, string } from 'yup';
import { yupResolver } from '@hookform/resolvers/yup';

import {
  ExposedAZProperties,
  ConfigureAvailabilityZoneField
} from './ConfigureAvailabilityZoneField';
import { ProviderCode, VPCSetupType } from '../../constants';
import { RegionOperation } from './constants';
import { YBInputField, YBModal, YBModalProps } from '../../../../../redesign/components';
import { YBReactSelectField } from '../../components/YBReactSelect/YBReactSelectField';
import { getRegionlabel, getRegionOptions, getZoneOptions } from './utils';

interface ConfigureRegionModalProps extends YBModalProps {
  onRegionSubmit: (region: CloudVendorRegionField) => void;
  onClose: () => void;
  providerCode: ProviderCode;
  regionOperation: RegionOperation;

  regionSelection?: CloudVendorRegionField;
  vpcSetupType?: VPCSetupType;
}

interface ConfigureRegionFormValues {
  fieldId: string;
  regionData: { value: { code: string; zoneOptions: string[] }; label: string };
  zones: ExposedAZProperties[];

  securityGroupId?: string;
  vnet?: string;
  ybImage?: string;
  sharedSubnet?: string;
}
type Region = Omit<ConfigureRegionFormValues, 'regionData'> & { code: string };
export interface CloudVendorRegionField extends Region {
  fieldId: string;
}

const useStyles = makeStyles((theme) => ({
  titleIcon: {
    color: theme.palette.orange[500]
  },
  formField: {
    marginTop: '10px',
    '&:first-child': {
      marginTop: 0
    }
  },
  manageAvailabilityZoneField: {
    marginTop: '10px'
  }
}));

export const ConfigureRegionModal = ({
  onRegionSubmit,
  onClose,
  regionOperation,
  providerCode,
  regionSelection,
  vpcSetupType,
  ...modalProps
}: ConfigureRegionModalProps) => {
  const fieldLabel = {
    region: 'Region',
    vnet: providerCode === ProviderCode.AZU ? 'Virtual Network Name' : 'VPC ID',
    securityGroupId:
      providerCode === ProviderCode.AZU ? 'Security Group Name (Optional)' : 'Security Group ID',
    ybImage:
      providerCode === ProviderCode.AWS
        ? 'Custom AMI ID (Optional)'
        : providerCode === ProviderCode.AZU
        ? 'Marketplace Image URN/Shared Gallery Image ID (optional)'
        : 'Custom Machine Image ID (Optional)',
    sharedSubnet: 'Shared Subnet'
  };
  const shouldExposeField: Record<keyof ConfigureRegionFormValues, boolean> = {
    fieldId: false,
    regionData: true,
    vnet: providerCode !== ProviderCode.GCP && vpcSetupType === VPCSetupType.EXISTING,
    securityGroupId: providerCode !== ProviderCode.GCP && vpcSetupType === VPCSetupType.EXISTING,
    ybImage: true,
    sharedSubnet: providerCode === ProviderCode.GCP,
    zones: providerCode !== ProviderCode.GCP
  };

  const validationSchema = object().shape({
    regionData: object().required(`${fieldLabel.region} is required.`),
    vnet: string().when([], {
      is: () => shouldExposeField.vnet,
      then: string().required(`${fieldLabel.vnet} is required.`)
    }),
    securityGroupId: string().when([], {
      is: () => shouldExposeField.securityGroupId && providerCode === ProviderCode.AWS,
      then: string().required(`${fieldLabel.securityGroupId} is required.`)
    }),
    sharedSubnet: string().when([], {
      is: () => shouldExposeField.sharedSubnet && providerCode === ProviderCode.GCP,
      then: string().required(`${fieldLabel.sharedSubnet} is required.`)
    }),
    zones: array().when([], {
      is: () => shouldExposeField.zones,
      then: array().min(1, 'Region configurations must contain at least one zone.')
    })
  });
  const formMethods = useForm<ConfigureRegionFormValues>({
    defaultValues: getDefaultFormValue(providerCode, regionSelection),
    resolver: yupResolver(validationSchema)
  });
  const classes = useStyles();

  const regionOptions = getRegionOptions(providerCode);

  const onSubmit: SubmitHandler<ConfigureRegionFormValues> = (data) => {
    const { regionData, ...region } = data;
    const newRegion =
      regionOperation === RegionOperation.ADD
        ? { ...region, code: regionData.value.code, fieldId: uuidv4() }
        : { ...region, code: regionData.value.code };
    if (providerCode === ProviderCode.GCP) {
      newRegion.zones = regionData.value.zoneOptions.map((zoneOption) => ({
        code: zoneOption,
        subnet: ''
      }));
    }
    onRegionSubmit(newRegion);
    formMethods.reset();
    onClose();
  };

  const selectedRegion = formMethods.watch('regionData');
  const selectedZones = formMethods.watch('zones');
  const setSelectedZones = (zones: ExposedAZProperties[]) => {
    formMethods.setValue('zones', zones);
  };

  return (
    <FormProvider {...formMethods}>
      <YBModal
        title="Add Region"
        titleIcon={<i className={clsx('fa fa-plus', classes.titleIcon)} />}
        submitLabel="Add Region"
        cancelLabel="Cancel"
        onSubmit={formMethods.handleSubmit(onSubmit)}
        onClose={onClose}
        submitTestId="ConfigureRegionModal-SubmitButton"
        cancelTestId="ConfigureRegionModal-CancelButton"
        {...modalProps}
      >
        {shouldExposeField.regionData && (
          <div className={classes.formField}>
            <div>{fieldLabel.region}</div>
            <YBReactSelectField
              control={formMethods.control}
              name="regionData"
              options={regionOptions}
            />
          </div>
        )}
        {shouldExposeField.vnet && (
          <div className={classes.formField}>
            <div>{fieldLabel.vnet}</div>
            <YBInputField
              control={formMethods.control}
              name="vnet"
              placeholder="Enter..."
              fullWidth
            />
          </div>
        )}
        {shouldExposeField.securityGroupId && (
          <div className={classes.formField}>
            <div>{fieldLabel.securityGroupId}</div>
            <YBInputField
              control={formMethods.control}
              name="securityGroupId"
              placeholder="Enter..."
              fullWidth
            />
          </div>
        )}
        {shouldExposeField.ybImage && (
          <div className={classes.formField}>
            <div>{fieldLabel.ybImage}</div>
            <YBInputField
              control={formMethods.control}
              name="ybImage"
              placeholder="Enter..."
              fullWidth
            />
          </div>
        )}
        {shouldExposeField.sharedSubnet && (
          <div className={classes.formField}>
            <div>{fieldLabel.sharedSubnet}</div>
            <YBInputField
              control={formMethods.control}
              name="sharedSubnet"
              placeholder="Enter..."
              fullWidth
            />
          </div>
        )}
        {shouldExposeField.zones && (
          <div>
            <ConfigureAvailabilityZoneField
              className={classes.manageAvailabilityZoneField}
              setSelectedZones={setSelectedZones}
              selectedZones={selectedZones ?? []}
              zoneCodeOptions={selectedRegion?.value?.zoneOptions}
              isSubmitting={formMethods.formState.isSubmitting}
            />
            {formMethods.formState.errors.zones?.message && (
              <FormHelperText error={true}>
                {formMethods.formState.errors.zones?.message}
              </FormHelperText>
            )}
          </div>
        )}
      </YBModal>
    </FormProvider>
  );
};

const getDefaultFormValue = (
  providerCode: ProviderCode,
  regionSelection: CloudVendorRegionField | undefined
) => {
  if (regionSelection === undefined) {
    return undefined;
  }
  const { code: currentRegionCode, ...currentRegion } = regionSelection;
  return {
    ...currentRegion,
    regionData: {
      value: {
        code: currentRegionCode,
        zoneOptions: getZoneOptions(providerCode, currentRegionCode)
      },
      label: getRegionlabel(providerCode, currentRegionCode)
    }
  };
};
