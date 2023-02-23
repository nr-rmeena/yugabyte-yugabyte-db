/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React from 'react';
import pluralize from 'pluralize';
import clsx from 'clsx';

import { EmptyListPlaceholder } from '../EmptyListPlaceholder';
import { BootstrapTable, TableHeaderColumn } from 'react-bootstrap-table';
import { YBButton } from '../../../common/forms/fields';
import { CloudVendorRegionField } from '../forms/configureRegion/ConfigureRegionModal';
import { ProviderCode, CloudVendorProviders } from '../constants';
import { K8sRegionField } from '../forms/configureRegion/ConfigureK8sRegionModal';

import styles from './RegionList.module.scss';

interface RegionListCommmonProps {
  showAddRegionFormModal: () => void;
  showEditRegionFormModal: () => void;
  showDeleteRegionModal: () => void;

  disabled?: boolean;
  isError?: boolean;
}
interface CloudVendorRegionListProps extends RegionListCommmonProps {
  providerCode: typeof CloudVendorProviders[number];
  regions: CloudVendorRegionField[];
  setRegionSelection: (regionSelection: CloudVendorRegionField) => void;
}
interface K8sRegionListProps extends RegionListCommmonProps {
  providerCode: typeof ProviderCode.KUBERNETES;
  regions: K8sRegionField[];
  setRegionSelection: (regionSelection: K8sRegionField) => void;
}
interface OnPremRegionListProps extends RegionListCommmonProps {
  providerCode: typeof ProviderCode.ON_PREM;
  regions: CloudVendorRegionField[];
  setRegionSelection: (regionSelection: CloudVendorRegionField) => void;
}

type RegionListProps = CloudVendorRegionListProps | K8sRegionListProps | OnPremRegionListProps;

export const RegionList = (props: RegionListProps) => {
  const { disabled, isError, regions, showAddRegionFormModal } = props;
  const { formatZones, formatRegionActions } = contextualHelpers(props);

  return regions.length === 0 && !disabled ? (
    <EmptyListPlaceholder
      actionButtonText={`Add Region`}
      descriptionText="Add regions to deploy DB nodes"
      onActionButtonClick={showAddRegionFormModal}
      className={clsx(isError && styles.emptyListError)}
    />
  ) : (
    <>
      <div className={styles.bootstrapTableContainer}>
        <BootstrapTable tableContainerClass={styles.bootstrapTable} data={regions}>
          <TableHeaderColumn dataField="code" isKey={true} dataSort={true}>
            Region
          </TableHeaderColumn>
          <TableHeaderColumn dataField="zones" dataFormat={formatZones}>
            Zones
          </TableHeaderColumn>
          <TableHeaderColumn
            columnClassName={styles.regionActionsColumn}
            dataFormat={formatRegionActions}
          />
        </BootstrapTable>
      </div>
    </>
  );
};

const contextualHelpers = ({
  disabled,
  providerCode,
  regions,
  setRegionSelection,
  showEditRegionFormModal,
  showDeleteRegionModal
}: RegionListProps) => {
  switch (providerCode) {
    case ProviderCode.AWS:
    case ProviderCode.AZU:
    case ProviderCode.GCP: {
      const handleEditRegion = (regionField: CloudVendorRegionField) => {
        setRegionSelection(regionField);
        showEditRegionFormModal();
      };
      const handleDeleteRegion = (regionField: CloudVendorRegionField) => {
        setRegionSelection(regionField);
        showDeleteRegionModal();
      };
      const formatZones = (zones: typeof regions[number]['zones']) =>
        pluralize('zone', zones.length, true);
      const formatRegionActions = (_: unknown, row: CloudVendorRegionField) => {
        return (
          <div className={styles.buttonContainer}>
            <YBButton
              className={clsx(disabled && styles.disabledButton)}
              btnIcon="fa fa-pencil"
              btnText="Edit"
              btnClass="btn btn-default"
              btnType="button"
              onClick={() => handleEditRegion(row)}
              disabled={disabled}
            />
            <YBButton
              className={clsx(disabled && styles.disabledButton)}
              btnIcon="fa fa-trash"
              btnText="Delete"
              btnClass="btn btn-default"
              btnType="button"
              onClick={() => handleDeleteRegion(row)}
              disabled={disabled}
            />
          </div>
        );
      };
      return {
        handleEditRegion: handleEditRegion,
        handleDeleteRegion: handleDeleteRegion,
        formatZones: formatZones,
        formatRegionActions: formatRegionActions
      };
    }
    case ProviderCode.KUBERNETES: {
      const handleEditRegion = (regionField: K8sRegionField) => {
        setRegionSelection(regionField);
        showEditRegionFormModal();
      };
      const handleDeleteRegion = (regionField: K8sRegionField) => {
        setRegionSelection(regionField);
        showDeleteRegionModal();
      };
      const formatZones = (zones: typeof regions[number]['zones']) =>
        pluralize('zone', zones.length, true);
      const formatRegionActions = (_: unknown, row: K8sRegionField) => {
        return (
          <div className={styles.buttonContainer}>
            <YBButton
              className={clsx(disabled && styles.disabledButton)}
              btnIcon="fa fa-pencil"
              btnText="Edit"
              btnClass="btn btn-default"
              btnType="button"
              onClick={() => handleEditRegion(row)}
              disabled={disabled}
            />
            <YBButton
              className={clsx(disabled && styles.disabledButton)}
              btnIcon="fa fa-trash"
              btnText="Delete"
              btnClass="btn btn-default"
              btnType="button"
              onClick={() => handleDeleteRegion(row)}
              disabled={disabled}
            />
          </div>
        );
      };
      return {
        handleEditRegion: handleEditRegion,
        handleDeleteRegion: handleDeleteRegion,
        formatZones: formatZones,
        formatRegionActions: formatRegionActions
      };
    }

    default:
      return {
        handleEditRegion: (regionField: CloudVendorRegionField | K8sRegionField) => null,
        handleDeleteRegion: (regionField: CloudVendorRegionField | K8sRegionField) => null,
        formatZones: (zones: typeof regions[number]['zones']) => '',
        formatRegionActions: (_: unknown, row: CloudVendorRegionField | K8sRegionField) => ''
      };
  }
};
