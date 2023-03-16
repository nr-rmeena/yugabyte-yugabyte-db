import axios, { Canceler } from 'axios';
import {
  YBProviderMutation,
  YBProvider,
  InstanceTypeMutation
} from '../../components/configRedesign/providerRedesign/types';
import { HostInfo, Provider as Provider_Deprecated, YBPSuccess } from './dtos';
import { ROOT_URL } from '../../config';
import {
  AvailabilityZone,
  Region,
  Universe,
  UniverseDetails,
  InstanceType,
  AccessKey,
  Certificate,
  KmsConfig,
  UniverseConfigure,
  HAConfig,
  HAReplicationSchedule,
  HAPlatformInstance,
  YBPTask
} from './dtos';
import { DEFAULT_RUNTIME_GLOBAL_SCOPE } from '../../actions/customers';

/**
 * @deprecated Use query key factories for more flexable key organization
 */
export enum QUERY_KEY {
  fetchUniverse = 'fetchUniverse',
  getProvidersList = 'getProvidersList',
  getRegionsList = 'getRegionsList',
  universeConfigure = 'universeConfigure',
  getInstanceTypes = 'getInstanceTypes',
  getDBVersions = 'getDBVersions',
  getDBVersionsByProvider = 'getDBVersionsByProvider',
  getAccessKeys = 'getAccessKeys',
  getCertificates = 'getCertificates',
  getKMSConfigs = 'getKMSConfigs',
  deleteCertificate = 'deleteCertificate',
  getHAConfig = 'getHAConfig',
  getHAReplicationSchedule = 'getHAReplicationSchedule',
  getHABackups = 'getHABackups',
  validateGflags = 'validateGflags',
  getMostUsedGflags = 'getMostUsedGflags',
  getAllGflags = 'getAllGflags',
  getGflagByName = 'getGlagByName'
}

// --------------------------------------------------------------------------------------
// React Query Key Factories
// --------------------------------------------------------------------------------------
// --------------------------------------------------------------------------------------
// TODO: Upgrade React Query to 3.17+ to get the change for supporting
//       annotating these as readonly query keys. (PLAT-4896)

export const providerQueryKey = {
  ALL: ['provider'],
  detail: (providerUUID: string) => [...providerQueryKey.ALL, providerUUID]
};

export const hostInfoQueryKey = {
  ALL: ['hostInfo']
};

export const universeQueryKey = {
  ALL: ['universe']
};

export const runtimeConfigQueryKey = {
  ALL: ['runtimeConfig'],
  customerScope: (customerUUID: string) => [...runtimeConfigQueryKey.ALL, 'customer', customerUUID]
};

export const instanceTypeQueryKey = {
  ALL: ['instanceType'],
  provider: (providerUUID: string) => [...instanceTypeQueryKey.ALL, 'provider', providerUUID]
};

class ApiService {
  private cancellers: Record<string, Canceler> = {};

  private getCustomerId(): string {
    const customerId = localStorage.getItem('customerId');
    return customerId || '';
  }

  fetchHostInfo = () => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/host_info`;
    return axios.get<HostInfo>(requestUrl).then((response) => response.data);
  };

  fetchRuntimeConfigs = (scope?: string, includeInherited = false) => {
    const configScope = scope || DEFAULT_RUNTIME_GLOBAL_SCOPE;
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/runtime_config/${configScope}?includeInherited=${includeInherited}`;
    return axios.get(requestUrl).then((response) => response.data);
  };

  findUniverseByName = (universeName: string): Promise<string[]> => {
    // auto-cancel previous request, if any
    if (this.cancellers.findUniverseByName) this.cancellers.findUniverseByName();

    // update cancellation stuff
    const source = axios.CancelToken.source();
    this.cancellers.findUniverseByName = source.cancel;

    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universes/find?name=${universeName}`;
    return axios
      .get<string[]>(requestUrl, { cancelToken: source.token })
      .then((resp) => resp.data);
  };

  fetchUniverseList = (): Promise<Universe[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universes`;
    return axios.get<Universe[]>(requestUrl).then((response) => response.data);
  };

  fetchUniverse = (universeUUID: string | undefined): Promise<Universe> => {
    if (universeUUID) {
      const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universes/${universeUUID}`;
      return axios.get<Universe>(requestUrl).then((resp) => resp.data);
    }
    return Promise.reject('Failed to fetch universe: No universe UUID provided.');
  };

  createProvider = (providerConfigMutation: YBProviderMutation, shouldValidate = false) => {
    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/providers`;
    return axios
      .post<YBPTask>(requestURL, providerConfigMutation, {
        params: {
          validate: shouldValidate
        }
      })
      .then((resp) => resp.data);
  };

  fetchProviderList = (): Promise<YBProvider[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers`;
    return axios.get<YBProvider[]>(requestUrl).then((resp) => resp.data);
  };

  deleteProvider = (providerUUID: string) => {
    if (providerUUID) {
      const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerUUID}`;
      return axios.delete<YBPTask>(requestURL).then((response) => response.data);
    }
    return Promise.reject('Failed to delete provider: No provider UUID provided.');
  };

  /**
   * @Deprecated This function uses an old provider type.
   */
  fetchProviderList_Deprecated = (): Promise<Provider_Deprecated[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers`;
    return axios.get<Provider_Deprecated[]>(requestUrl).then((resp) => resp.data);
  };

  fetchProvider = (providerUUID: string | undefined): Promise<YBProvider> => {
    if (providerUUID) {
      const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerUUID}`;
      return axios.get<YBProvider>(requestUrl).then((resp) => resp.data);
    }
    return Promise.reject('Failed to fetch provider: No provider UUID provided.');
  };

  fetchProviderRegions = (providerId?: string): Promise<Region[]> => {
    if (providerId) {
      const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerId}/regions`;
      return axios.get<Region[]>(requestUrl).then((resp) => resp.data);
    } else {
      return Promise.reject('Failed to fetch provider regions: No provider UUID provided.');
    }
  };

  createInstanceType = (providerUUID: string, instanceType: InstanceTypeMutation) => {
    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerUUID}/instance_types`;
    return axios.post<InstanceType>(requestURL, instanceType).then((response) => response.data);
  };

  fetchInstanceTypes = (providerUUID?: string): Promise<InstanceType[]> => {
    if (providerUUID) {
      const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerUUID}/instance_types`;
      return axios.get<InstanceType[]>(requestURL).then((response) => response.data);
    } else {
      return Promise.reject('Failed to fetch provider regions: No provider UUID provided');
    }
  };

  deleteInstanceType = (providerUUID: string, instanceTypeCode: string) => {
    if (providerUUID && instanceTypeCode) {
      const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerUUID}/instance_types/${instanceTypeCode}`;
      return axios.delete<YBPSuccess>(requestURL).then((response) => response.data);
    } else {
      const errorMessage = providerUUID
        ? 'No instance type code provided'
        : 'No provider UUID provided';
      return Promise.reject(`Failed to fetch provider regions: ${errorMessage}`);
    }
  };

  getAZList = (providerId: string, regionId: string): Promise<AvailabilityZone[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerId}/regions/${regionId}/zones`;
    return axios.get<AvailabilityZone[]>(requestUrl).then((resp) => resp.data);
  };

  universeConfigure = (data: UniverseConfigure): Promise<UniverseDetails> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universe_configure`;
    return axios.post<UniverseDetails>(requestUrl, data).then((resp) => resp.data);
  };

  universeCreate = (data: UniverseConfigure): Promise<Universe> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universes`;
    return axios.post<Universe>(requestUrl, data).then((resp) => resp.data);
  };

  universeEdit = (data: UniverseConfigure, universeId: string): Promise<Universe> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/universes/${universeId}`;
    return axios.put<Universe>(requestUrl, data).then((resp) => resp.data);
  };

  getDBVersions = (): Promise<string[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/releases`;
    return axios.get<string[]>(requestUrl).then((resp) => resp.data);
  };

  getDBVersionsByProvider = (providerId?: string): Promise<string[]> => {
    if (providerId) {
      const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerId}/releases`;
      return axios.get<string[]>(requestUrl).then((resp) => resp.data);
    } else {
      return Promise.reject('Querying access keys failed: no provider ID provided');
    }
  };

  getAccessKeys = (providerId?: string): Promise<AccessKey[]> => {
    if (providerId) {
      const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/providers/${providerId}/access_keys`;
      return axios.get<AccessKey[]>(requestUrl).then((resp) => resp.data);
    } else {
      return Promise.reject('Querying access keys failed: no provider ID provided');
    }
  };

  getCertificates = (): Promise<Certificate[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/certificates`;
    return axios.get<Certificate[]>(requestUrl).then((resp) => resp.data);
  };

  getKMSConfigs = (): Promise<KmsConfig[]> => {
    const requestUrl = `${ROOT_URL}/customers/${this.getCustomerId()}/kms_configs`;
    return axios.get<KmsConfig[]>(requestUrl).then((resp) => resp.data);
  };

  createHAConfig = (clusterKey: string): Promise<HAConfig> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config`;
    const payload = { cluster_key: clusterKey };
    return axios.post<HAConfig>(requestUrl, payload).then((resp) => resp.data);
  };

  getHAConfig = (): Promise<HAConfig> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config`;
    return axios.get<HAConfig>(requestUrl).then((resp) => resp.data);
  };

  deleteHAConfig = (configId: string): Promise<void> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}`;
    return axios.delete(requestUrl);
  };

  createHAInstance = (
    configId: string,
    address: string,
    isLeader: boolean,
    isLocal: boolean
  ): Promise<HAPlatformInstance> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/instance`;
    const payload = {
      address,
      is_leader: isLeader,
      is_local: isLocal
    };
    return axios.post<HAPlatformInstance>(requestUrl, payload).then((resp) => resp.data);
  };

  deleteHAInstance = (configId: string, instanceId: string): Promise<void> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/instance/${instanceId}`;
    return axios.delete(requestUrl);
  };

  promoteHAInstance = (configId: string, instanceId: string, backupFile: string): Promise<void> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/instance/${instanceId}/promote`;
    const payload = { backup_file: backupFile };
    return axios.post(requestUrl, payload);
  };

  getHABackups = (configId: string): Promise<string[]> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/backup/list`;
    return axios.get<string[]>(requestUrl).then((resp) => resp.data);
  };

  getHAReplicationSchedule = (configId?: string): Promise<HAReplicationSchedule> => {
    if (configId) {
      const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/replication_schedule`;
      return axios.get<HAReplicationSchedule>(requestUrl).then((resp) => resp.data);
    } else {
      return Promise.reject('Querying HA replication schedule failed: no config ID provided');
    }
  };

  startHABackupSchedule = (
    configId?: string,
    replicationFrequency?: number
  ): Promise<HAReplicationSchedule> => {
    if (configId && replicationFrequency) {
      const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/replication_schedule/start`;
      const payload = { frequency_milliseconds: replicationFrequency };
      return axios.put<HAReplicationSchedule>(requestUrl, payload).then((resp) => resp.data);
    } else {
      return Promise.reject(
        'Start HA backup schedule failed: no config ID or replication frequency provided'
      );
    }
  };

  stopHABackupSchedule = (configId: string): Promise<HAReplicationSchedule> => {
    const requestUrl = `${ROOT_URL}/settings/ha/config/${configId}/replication_schedule/stop`;
    return axios.put<HAReplicationSchedule>(requestUrl).then((resp) => resp.data);
  };

  generateHAKey = (): Promise<Pick<HAConfig, 'cluster_key'>> => {
    const requestUrl = `${ROOT_URL}/settings/ha/generate_key`;
    return axios.get<Pick<HAConfig, 'cluster_key'>>(requestUrl).then((resp) => resp.data);
  };

  // check if exception was caused by canceling previous request
  isRequestCancelError(error: unknown): boolean {
    return axios.isCancel(error);
  }

  /**
   * Delete certificate which is not attched to any universe.
   *
   * @param certUUID - certificate UUID
   */
  deleteCertificate = (certUUID: string, customerUUID: string): Promise<any> => {
    const requestUrl = `${ROOT_URL}/customers/${customerUUID}/certificates/${certUUID}`;
    return axios.delete<any>(requestUrl).then((res) => res.data);
  };

  getAlerts = (
    offset: number,
    limit: number,
    sortBy: string,
    direction = 'ASC',
    filter: {}
  ): Promise<any> => {
    const payload = {
      filter,
      sortBy,
      direction,
      offset,
      limit,
      needTotalCount: true
    };

    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/alerts/page`;
    return axios.post(requestURL, payload).then((res) => res.data);
  };

  getAlertCount = (filter: {}): Promise<any> => {
    const payload = {
      ...filter
    };
    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/alerts/count`;
    return axios.post(requestURL, payload).then((res) => res.data);
  };

  getAlert = (alertUUID: string) => {
    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/alerts/${alertUUID}`;
    return axios.get(requestURL).then((res) => res.data);
  };

  acknowledgeAlert = (uuid: string) => {
    const requestURL = `${ROOT_URL}/customers/${this.getCustomerId()}/alerts/acknowledge`;
    return axios.post(requestURL, { uuids: [uuid] }).then((res) => res.data);
  };
}

export const api = new ApiService();
