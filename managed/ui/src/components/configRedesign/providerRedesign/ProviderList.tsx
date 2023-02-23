/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React, { useState } from 'react';
import { useQuery } from 'react-query';
import { BootstrapTable, TableHeaderColumn } from 'react-bootstrap-table';
import { Dropdown, MenuItem } from 'react-bootstrap';
import { Link } from 'react-router';
import { Box } from '@material-ui/core';

import { api, providerQueryKey, universeQueryKey } from '../../../redesign/helpers/api';
import { YBErrorIndicator, YBLoading } from '../../common/indicators';
import {
  ProviderLabel,
  CloudVendorProviders,
  ProviderCode,
  KubernetesProviderType,
  KUBERNETES_PROVIDERS_MAP,
  PROVIDER_ROUTE_PREFIX
} from './constants';
import { EmptyListPlaceholder } from './EmptyListPlaceholder';
import { ProviderDashboardView } from './InfraProvider';
import { RegionsCell } from './RegionsCell';
import { YBLabelWithIcon } from '../../common/descriptors';
import ellipsisIcon from '../../common/media/more.svg';
import { DeleteProviderConfigModal } from './DeleteProviderConfigModal';
import { UniverseItem } from './providerDetail/UniverseTable';

import { Universe } from '../../../redesign/helpers/dtos';
import { YBProvider, YBRegion } from './types';

import styles from './ProviderList.module.scss';

interface ProviderListCommonProps {
  setCurrentView: (newView: ProviderDashboardView) => void;
}
interface CloudVendorProviderListProps extends ProviderListCommonProps {
  providerCode: typeof CloudVendorProviders[number];
}
interface K8sProviderListProps extends ProviderListCommonProps {
  providerCode: typeof ProviderCode.KUBERNETES;
  kubernetesProviderType: KubernetesProviderType;
}
type ProviderListProps = CloudVendorProviderListProps | K8sProviderListProps;

export const ProviderList = (props: ProviderListProps) => {
  const { providerCode, setCurrentView } = props;
  const [isDeleteProviderModalOpen, setIsDeleteProviderModalOpen] = useState<boolean>(false);
  const [deleteProviderConfigSelection, setDeleteProviderConfigSelection] = useState<YBProvider>();
  const {
    data: providerList,
    isLoading: isProviderListQueryLoading,
    isError: isProviderListQueryError,
    isIdle: isProviderListQueryIdle
  } = useQuery(providerQueryKey.ALL, () => api.fetchProviderList());
  const {
    data: universeList,
    isLoading: isUniverseListQueryLoading,
    isError: isUniverseListQueryError,
    isIdle: isUniverseListQueryIdle
  } = useQuery(universeQueryKey.ALL, () => api.fetchUniverseList());

  if (
    isProviderListQueryLoading ||
    isProviderListQueryIdle ||
    isUniverseListQueryLoading ||
    isUniverseListQueryIdle
  ) {
    return <YBLoading />;
  }
  if (isProviderListQueryError) {
    return <YBErrorIndicator customErrorMessage="Error fetching provider list." />;
  }
  if (isUniverseListQueryError) {
    return <YBErrorIndicator customErrorMessage="Error fetching universe list." />;
  }

  const showDeleteProviderModal = () => {
    setIsDeleteProviderModalOpen(true);
  };
  const hideDeleteProviderModal = () => {
    setIsDeleteProviderModalOpen(false);
  };
  const handleDeleteProviderConfig = (providerConfig: YBProvider) => {
    setDeleteProviderConfigSelection(providerConfig);
    showDeleteProviderModal();
  };
  const handleCreateProviderAction = () => {
    setCurrentView(ProviderDashboardView.CREATE);
  };

  const formatProviderName = (providerName: string, row: YBProvider) => {
    return (
      <Link
        to={`/${PROVIDER_ROUTE_PREFIX}/${
          providerCode === ProviderCode.KUBERNETES ? props.kubernetesProviderType : providerCode
        }/${row.uuid}`}
      >
        {providerName}
      </Link>
    );
  };
  const formatRegions = (regions: YBRegion[]) => <RegionsCell regions={regions} />;
  const formatProviderActions = (_: unknown, row: YBProvider) => (
    <Dropdown id="table-actions-dropdown" pullRight>
      <Dropdown.Toggle noCaret>
        <img src={ellipsisIcon} alt="more" className="ellipsis-icon" />
      </Dropdown.Toggle>
      <Dropdown.Menu>
        <MenuItem eventKey="1" onClick={() => handleDeleteProviderConfig(row)}>
          <YBLabelWithIcon icon="fa fa-trash">Delete Configuration</YBLabelWithIcon>
        </MenuItem>
      </Dropdown.Menu>
    </Dropdown>
  );

  const formatUsage = (_: unknown, row: YBProvider) => {
    const linkedUniverses = getLinkedUniverses(row.uuid, universeList);
    return linkedUniverses.length ? (
      <Box display="flex" gridGap="5px">
        <div>In Use</div>
        <Box
          height="fit-content"
          padding="4px 6px"
          fontSize="10px"
          borderRadius="6px"
          bgcolor="#e9eef2"
        >
          {linkedUniverses.length}
        </Box>
      </Box>
    ) : (
      'Not in Use'
    );
  };

  const filteredProviderList = providerList.filter((provider) =>
    providerCode === ProviderCode.KUBERNETES
      ? provider.code === providerCode &&
        (KUBERNETES_PROVIDERS_MAP[props.kubernetesProviderType] as readonly string[]).includes(
          provider.details.cloudInfo.kubernetes.kubernetesProvider
        )
      : provider.code === providerCode
  );
  return filteredProviderList.length === 0 ? (
    <EmptyListPlaceholder
      actionButtonText={`Create ${ProviderLabel[providerCode]} Config`}
      descriptionText={`No ${ProviderLabel[providerCode]} config to show`}
      onActionButtonClick={handleCreateProviderAction}
    />
  ) : (
    <>
      <div className={styles.bootstrapTableContainer}>
        <BootstrapTable tableContainerClass={styles.bootstrapTable} data={filteredProviderList}>
          <TableHeaderColumn
            dataField="name"
            isKey={true}
            dataSort={true}
            dataFormat={formatProviderName}
          >
            Configuration Name
          </TableHeaderColumn>
          <TableHeaderColumn dataField="regions" dataFormat={formatRegions}>
            Regions
          </TableHeaderColumn>
          <TableHeaderColumn dataFormat={formatUsage}>Usage</TableHeaderColumn>
          <TableHeaderColumn
            columnClassName={styles.providerActionsColumn}
            dataFormat={formatProviderActions}
            width="50"
          />
        </BootstrapTable>
      </div>
      <DeleteProviderConfigModal
        open={isDeleteProviderModalOpen}
        onClose={hideDeleteProviderModal}
        providerConfig={deleteProviderConfigSelection}
      />
    </>
  );
};

const getLinkedUniverses = (providerUUID: string, universes: Universe[]) =>
  universes.reduce((linkedUniverses: UniverseItem[], universe) => {
    const linkedClusters = universe.universeDetails.clusters.filter(
      (cluster) => cluster.userIntent.provider === providerUUID
    );
    if (linkedClusters.length) {
      linkedUniverses.push({
        ...universe,
        linkedClusters: linkedClusters
      });
    }
    return linkedUniverses;
  }, []);
