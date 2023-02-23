/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from 'react-query';
import { toast } from 'react-toastify';
import axios, { AxiosError } from 'axios';

import { api, providerQueryKey } from '../../../redesign/helpers/api';
import { assertUnreachableCase } from '../../../utils/ErrorUtils';
import { YBErrorIndicator, YBLoading } from '../../common/indicators';
import {
  KubernetesProviderType,
  ProviderLabel,
  CloudVendorProviders,
  ProviderCode
} from './constants';
import { ProviderListView } from './ProviderListView';
import { fetchTaskUntilItCompletes } from '../../../actions/xClusterReplication';
import { ProviderCreateView } from './ProviderCreateView';

import { YBProviderMutation } from './types';
import { ResourceCreationResponse } from '../../../redesign/helpers/dtos';

import styles from './InfraProvider.module.scss';

type InfraProviderProps =
  | {
      providerCode: typeof CloudVendorProviders[number];
    }
  | {
      providerCode: typeof ProviderCode.KUBERNETES;
      kubernetesProviderType: KubernetesProviderType;
    };

export type CreateInfraProvider = (
  values: YBProviderMutation,
  options?: {
    onSettled?: (data: ResourceCreationResponse | undefined) => void;
    onSuccess?: (data: ResourceCreationResponse) => void;
    onError?: (error: Error | AxiosError) => void;
  }
) => void;

export const ProviderDashboardView = {
  LIST: 'list',
  CREATE: 'create'
} as const;
export type ProviderDashboardView = typeof ProviderDashboardView[keyof typeof ProviderDashboardView];

const DEFAULT_VIEW = ProviderDashboardView.LIST;

export const InfraProvider = (props: InfraProviderProps) => {
  const { providerCode } = props;
  const [currentView, setCurrentView] = useState<ProviderDashboardView>(DEFAULT_VIEW);

  const queryClient = useQueryClient();
  const providerListQuery = useQuery(providerQueryKey.ALL, () => api.fetchProviderList());
  const providerMutation = useMutation((values: YBProviderMutation) => api.createProvider(values), {
    onSuccess: (response) => {
      setCurrentView(ProviderDashboardView.LIST);
      queryClient.invalidateQueries(providerQueryKey.ALL);

      fetchTaskUntilItCompletes(response.taskUUID, (error: boolean) => {
        if (error) {
          toast.error(
            <span className={styles.alertMsg}>
              <i className="fa fa-exclamation-circle" />
              <span>{`${ProviderLabel[providerCode]} provider creation failed.`}</span>
              <a href={`/tasks/${response.taskUUID}`} rel="noopener noreferrer" target="_blank">
                View Details
              </a>
            </span>
          );
        }
        queryClient.invalidateQueries(providerQueryKey.ALL);
      });
    },
    onError: (error: Error | AxiosError) => {
      if (axios.isAxiosError(error)) {
        toast.error(error.response?.data?.error?.message ?? error.message);
      } else {
        toast.error(error.message);
      }
    }
  });

  if (providerListQuery.isLoading || providerListQuery.isIdle) {
    return <YBLoading />;
  }

  if (providerListQuery.isError) {
    return <YBErrorIndicator customErrorMessage="Error fetching provider list" />;
  }

  const createInfraProvider = async (
    values: YBProviderMutation,
    options?: {
      onSettled?: (data: ResourceCreationResponse | undefined) => void;
      onSuccess?: (data: ResourceCreationResponse) => void;
      onError?: (error: Error | AxiosError) => void;
    }
  ) => {
    providerMutation.mutate(values, options);
  };
  const handleOnBack = () => {
    setCurrentView(DEFAULT_VIEW);
  };

  switch (currentView) {
    case ProviderDashboardView.LIST:
      return providerCode === ProviderCode.KUBERNETES ? (
        <ProviderListView
          providerCode={providerCode}
          setCurrentView={setCurrentView}
          kubernetesProviderType={props.kubernetesProviderType}
        />
      ) : (
        <ProviderListView providerCode={providerCode} setCurrentView={setCurrentView} />
      );

    case ProviderDashboardView.CREATE:
      return providerCode === ProviderCode.KUBERNETES ? (
        <ProviderCreateView
          providerCode={providerCode}
          handleOnBack={handleOnBack}
          createInfraProvider={createInfraProvider}
          kubernetesProviderType={props.kubernetesProviderType}
        />
      ) : (
        <ProviderCreateView
          providerCode={providerCode}
          handleOnBack={handleOnBack}
          createInfraProvider={createInfraProvider}
        />
      );
    default:
      return assertUnreachableCase(currentView);
  }
};
