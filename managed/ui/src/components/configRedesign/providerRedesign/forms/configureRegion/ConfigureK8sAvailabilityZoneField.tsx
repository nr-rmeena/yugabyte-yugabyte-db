/*
 * Copyright 2023 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React from 'react';
import clsx from 'clsx';
import { Divider, makeStyles, Typography } from '@material-ui/core';
import { YBButton } from '../../../../common/forms/fields';
import { useFieldArray, useFormContext } from 'react-hook-form';

import { K8sRegionField } from './ConfigureK8sRegionModal';
import { K8sCertIssuerType, K8sCertIssuerTypeLabel, K8sRegionFieldLabel } from './constants';
import { OptionProps, YBInputField, YBRadioGroupField } from '../../../../../redesign/components';
// import { YBDropZoneField } from '../../components/YBDropZone/YBDropZoneField';

interface ConfigureK8sAvailabilityZoneFieldProps {
  isSubmitting: boolean;
  className?: string;
}

const useStyles = makeStyles(() => ({
  formField: {
    marginTop: '10px',
    '&:first-child': {
      marginTop: 0
    }
  },
  zonesContainer: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',

    marginTop: '10px'
  },
  zoneConfigContainer: {
    display: 'flex',
    gap: '10px'
  },
  addZoneButton: {
    marginTop: '10px'
  }
}));

const CERT_ISSUER_TYPE_OPTIONS: OptionProps[] = [
  {
    value: K8sCertIssuerType.NONE,
    label: K8sCertIssuerTypeLabel[K8sCertIssuerType.NONE]
  },
  {
    value: K8sCertIssuerType.ISSUER,
    label: K8sCertIssuerTypeLabel[K8sCertIssuerType.ISSUER]
  },
  {
    value: K8sCertIssuerType.CLUSTER_ISSUER,
    label: K8sCertIssuerTypeLabel[K8sCertIssuerType.CLUSTER_ISSUER]
  }
];

export const ConfigureK8sAvailabilityZoneField = ({
  isSubmitting,
  className
}: ConfigureK8sAvailabilityZoneFieldProps) => {
  const classes = useStyles();
  const { control, watch } = useFormContext<K8sRegionField>();
  const { fields, append } = useFieldArray({ control, name: 'zones' });

  const addZoneField = () => {
    append({ code: '', certIssuerType: K8sCertIssuerType.NONE });
  };
  const zones = watch('zones', []);
  return (
    <div className={clsx(className)}>
      <div className={classes.zonesContainer}>
        <Typography variant="h5">Availability Zones</Typography>
        {fields.map((zone, index) => (
          <div key={zone.id}>
            {index !== 0 && <Divider />}
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.ZONE_CODE}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.code`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            {/* <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.KUBE_CONFIG_CONTENT}</div>
              <YBDropZoneField
                name={`zones.${index}.kubeConfigContent`}
                control={control}
                actionButtonText="Upload Kube Config File"
                multipleFiles={false}
                showHelpText={false}
              />
            </div> */}
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.KUBE_DOMAIN}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.kubernetesStorageClasses`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.KUBE_POD_ADDRESS_TEMPLATE}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.kubePodAddressTemplate`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.KUBE_DOMAIN}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.kubeDomain`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.KUBE_NAMESPACE}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.kubeNamespace`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.OVERRIDES}</div>
              <YBInputField
                control={control}
                name={`zones.${index}.overrides`}
                placeholder="Enter..."
                fullWidth
              />
            </div>
            <div className={classes.formField}>
              <div>{K8sRegionFieldLabel.CERT_ISSUER_TYPE}</div>
              <YBRadioGroupField
                control={control}
                name={`zones.${index}.certIssuerType`}
                options={CERT_ISSUER_TYPE_OPTIONS}
                orientation="horizontal"
              />
            </div>
            {([
              K8sCertIssuerType.CLUSTER_ISSUER,
              K8sCertIssuerType.ISSUER
            ] as K8sCertIssuerType[]).includes(zones?.[index].certIssuerType) && (
              <div className={classes.formField}>
                <div>{K8sRegionFieldLabel.CERT_ISSUER_NAME}</div>
                <YBInputField
                  control={control}
                  name={`zones.${index}.certIssuerName`}
                  placeholder="Enter..."
                  fullWidth
                />
              </div>
            )}
          </div>
        ))}
      </div>
      <YBButton
        className={classes.addZoneButton}
        btnIcon="fa fa-plus"
        btnText="Add Zone"
        btnClass="btn btn-default"
        btnType="button"
        onClick={addZoneField}
        disabled={isSubmitting}
        data-testId="ConfigureK8sAvailabilityZoneField-AddZoneButton"
      />
    </div>
  );
};
