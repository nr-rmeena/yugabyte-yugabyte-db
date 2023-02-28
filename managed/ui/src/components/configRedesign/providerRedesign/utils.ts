/*
 * Copyright 2023 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import { YBAHost } from '../../../redesign/helpers/constants';
import { HostInfo } from '../../../redesign/helpers/dtos';
import { NTPSetupType, ProviderCode, CloudVendorProviders } from './constants';
import { YBProvider, YBProviderMutation } from './types';

export const getNtpSetupType = (providerConfig: YBProvider): NTPSetupType => {
  if (
    providerConfig.code === ProviderCode.KUBERNETES ||
    providerConfig.details.setUpChrony === false
  ) {
    return NTPSetupType.NO_NTP;
  }
  if (providerConfig.details.ntpServers.length) {
    return NTPSetupType.SPECIFIED;
  }
  return NTPSetupType.CLOUD_VENDOR;
};

// The public cloud providers (AWS, GCP, AZU) each provide their own NTP server which users may opt use
export const hasDefaultNTPServers = (providerCode: ProviderCode) =>
  (CloudVendorProviders as readonly ProviderCode[]).includes(providerCode);

// TODO: API should return the YBA host as part of the hostInfo response.
export const getYBAHost = (hostInfo: HostInfo) => {
  if (!(typeof hostInfo.gcp === 'string' || hostInfo.gcp instanceof String)) {
    return YBAHost.GCP;
  }
  if (!(typeof hostInfo.gcp === 'string' || hostInfo.gcp instanceof String)) {
    return YBAHost.AWS;
  }
  return YBAHost.SELF_HOSTED;
};

export const getIntraProviderTab = (providerConfig: YBProvider | YBProviderMutation) =>
  providerConfig.code === ProviderCode.KUBERNETES
    ? providerConfig.details.cloudInfo.kubernetes.kubernetesProvider
    : providerConfig.code;
