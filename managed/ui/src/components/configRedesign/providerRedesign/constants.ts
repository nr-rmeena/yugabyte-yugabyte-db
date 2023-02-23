export const CONFIG_ROUTE_PREFIX = 'config';

export const ConfigTabKey = {
  INFRA: 'infra',
  BACKUP: 'backup',
  BACKUP_NEW: 'backupNew',
  SECURITY: 'security'
} as const;
export type ConfigTabKey = typeof ConfigTabKey[keyof typeof ConfigTabKey];

/**
 * Values correspond to the provider codes expected on the the YBA backend.
 */
export const ProviderCode = {
  UNKNOWN: 'unknown',
  AWS: 'aws',
  GCP: 'gcp',
  AZU: 'azu',
  DOCKER: 'docker',
  ON_PREM: 'onprem',
  KUBERNETES: 'kubernetes',
  CLOUD: 'cloud-1',
  OTHER: 'other'
} as const;
export type ProviderCode = typeof ProviderCode[keyof typeof ProviderCode];

export const CloudVendorProviders = [ProviderCode.AWS, ProviderCode.AZU, ProviderCode.GCP] as const;

// `KubernetesProviderType` and `KubernetesProviderTab` are required because we need to support
// 3 kubernetes provider tabs
// - Tanzu
// - OpenShift
// - Managed Kubernetes Service (GKE, AKS, EKS, Custom)
// in addition to the limited support for deprecated kubernetes providers like PKS
export const KubernetesProviderType = {
  TANZU: 'k8sTanzu',
  OPEN_SHIFT: 'k8sOpenshift',
  MANAGED_SERVICE: 'k8sManagedService',
  DEPRECATED: 'k8sDeprecated'
} as const;
export type KubernetesProviderType = typeof KubernetesProviderType[keyof typeof KubernetesProviderType];

export type KubernetesProviderTab = Exclude<
  KubernetesProviderType,
  typeof KubernetesProviderType.DEPRECATED
>;

// --------------------------------------------------------------------------------------
// Route Constants
// --------------------------------------------------------------------------------------
export const PROVIDER_ROUTE_PREFIX = `${CONFIG_ROUTE_PREFIX}/${ConfigTabKey.INFRA}`;

// --------------------------------------------------------------------------------------
// Provider Field & Form Constants
// --------------------------------------------------------------------------------------
export const ArchitectureType = {
  X86_64: 'x86_64',
  ARM64: 'aarch64'
} as const;
export type ArchitectureType = typeof ArchitectureType[keyof typeof ArchitectureType];

export const NTPSetupType = {
  CLOUD_VENDOR: 'cloudVendor',
  SPECIFIED: 'specified',
  NO_NTP: 'noNTP'
} as const;
export type NTPSetupType = typeof NTPSetupType[keyof typeof NTPSetupType];

export const VPCSetupType = {
  EXISTING: 'existingVPC',
  HOST_INSTANCE: 'hostInstanceVPC',
  NEW: 'newVPC'
} as const;
export type VPCSetupType = typeof VPCSetupType[keyof typeof VPCSetupType];

export const KubernetesProvider = {
  AKS: 'aks',
  CUSTOM: 'custom',
  EKS: 'eks',
  GKE: 'gke',
  OPEN_SHIFT: 'openshift',
  PKS: 'pks',
  TANZU: 'tanzu'
} as const;
export type KubernetesProvider = typeof KubernetesProvider[keyof typeof KubernetesProvider];

export const KUBERNETES_PROVIDERS_MAP = {
  [KubernetesProviderType.DEPRECATED]: [KubernetesProvider.PKS],
  [KubernetesProviderType.MANAGED_SERVICE]: [
    KubernetesProvider.AKS,
    KubernetesProvider.CUSTOM,
    KubernetesProvider.EKS,
    KubernetesProvider.GKE
  ],
  [KubernetesProviderType.OPEN_SHIFT]: [KubernetesProvider.OPEN_SHIFT],
  [KubernetesProviderType.TANZU]: [KubernetesProvider.TANZU]
} as const;

/**
 * A field name for storing server errors from form submission.
 */
export const ASYNC_ERROR = 'asyncError';

// --------------------------------------------------------------------------------------
// User Facing Labels
// --------------------------------------------------------------------------------------
export const ProviderLabel = {
  [ProviderCode.AWS]: 'AWS',
  [ProviderCode.AZU]: 'AZU',
  [ProviderCode.GCP]: 'GCP',
  [ProviderCode.KUBERNETES]: 'Kubernetes',
  [ProviderCode.ON_PREM]: 'On Prem'
} as const;

export const NTPSetupTypeLabel = {
  [NTPSetupType.SPECIFIED]: 'Specify Custom NTP Server(s)',
  [NTPSetupType.NO_NTP]: 'Assume NTP server configured in machine image', // Assume NTP server configured in machine image
  [NTPSetupType.CLOUD_VENDOR]: (providerCode: string) =>
    `Use ${ProviderLabel[providerCode]}'s NTP Server` // Use {Cloud Vendor}'s NTP Server
} as const;

export const VPCSetupTypeLabel = {
  [VPCSetupType.EXISTING]: 'Specify an existing VPC',
  [VPCSetupType.HOST_INSTANCE]: 'Use VPC from YBA host instance',
  [VPCSetupType.NEW]: 'Create a new VPC (Beta)'
};

export const KubernetesProviderLabel = {
  [KubernetesProvider.AKS]: 'Azure Kubernetes Service',
  [KubernetesProvider.CUSTOM]: 'Custom Kubernetes Service',
  [KubernetesProvider.EKS]: 'Elastic Kubernetes Service',
  [KubernetesProvider.GKE]: 'Google Kubernetes Engine',
  [KubernetesProvider.OPEN_SHIFT]: 'Red Hat OpenShift',
  [KubernetesProvider.PKS]: 'Pivotal Container Service',
  [KubernetesProvider.TANZU]: 'VMware Tanzu'
} as const;

export const KubernetesProviderTypeLabel = {
  [KubernetesProviderType.MANAGED_SERVICE]: 'Managed Kubernetes Service',
  [KubernetesProviderType.OPEN_SHIFT]: 'Red Hat OpenShift',
  [KubernetesProviderType.TANZU]: 'VMWare Tanzu'
} as const;
