// Copyright (c) YugaByte, Inc.
package com.yugabyte.yw.api.v2;

import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.ModelFactory.newProvider;
import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.yugabyte.yba.v2.client.ApiClient;
import com.yugabyte.yba.v2.client.ApiException;
import com.yugabyte.yba.v2.client.Configuration;
import com.yugabyte.yba.v2.client.api.UniverseManagementApi;
import com.yugabyte.yba.v2.client.models.AuditLogConfig;
import com.yugabyte.yba.v2.client.models.AvailabilityZoneGFlags;
import com.yugabyte.yba.v2.client.models.ClusterGFlags;
import com.yugabyte.yba.v2.client.models.ClusterInfo;
import com.yugabyte.yba.v2.client.models.ClusterNetworkingSpec;
import com.yugabyte.yba.v2.client.models.ClusterNetworkingSpec.EnableExposingServiceEnum;
import com.yugabyte.yba.v2.client.models.ClusterProviderSpec;
import com.yugabyte.yba.v2.client.models.ClusterSpec;
import com.yugabyte.yba.v2.client.models.ClusterSpec.ClusterTypeEnum;
import com.yugabyte.yba.v2.client.models.ClusterStorageSpec;
import com.yugabyte.yba.v2.client.models.ClusterStorageSpec.StorageTypeEnum;
import com.yugabyte.yba.v2.client.models.CommunicationPortsSpec;
import com.yugabyte.yba.v2.client.models.EncryptionAtRestSpec;
import com.yugabyte.yba.v2.client.models.EncryptionAtRestSpec.OpTypeEnum;
import com.yugabyte.yba.v2.client.models.EncryptionAtRestSpec.TypeEnum;
import com.yugabyte.yba.v2.client.models.EncryptionInTransitSpec;
import com.yugabyte.yba.v2.client.models.PlacementAZ;
import com.yugabyte.yba.v2.client.models.PlacementCloud;
import com.yugabyte.yba.v2.client.models.PlacementInfo;
import com.yugabyte.yba.v2.client.models.PlacementRegion;
import com.yugabyte.yba.v2.client.models.UniverseCreateSpec;
import com.yugabyte.yba.v2.client.models.UniverseCreateSpec.ArchEnum;
import com.yugabyte.yba.v2.client.models.UniverseCreateSpecYcql;
import com.yugabyte.yba.v2.client.models.UniverseCreateSpecYsql;
import com.yugabyte.yba.v2.client.models.UniverseInfo;
import com.yugabyte.yba.v2.client.models.UniverseLogsExporterConfig;
import com.yugabyte.yba.v2.client.models.UniverseResp;
import com.yugabyte.yba.v2.client.models.UniverseSpec;
import com.yugabyte.yba.v2.client.models.YBPTask;
import com.yugabyte.yba.v2.client.models.YCQLAuditConfig;
import com.yugabyte.yba.v2.client.models.YSQLAuditConfig;
import com.yugabyte.yba.v2.client.models.YcqlSpec;
import com.yugabyte.yba.v2.client.models.YsqlSpec;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.cloud.PublicCloudConstants.Architecture;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase.ServerType;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.TestHelper;
import com.yugabyte.yw.common.certmgmt.CertConfigType;
import com.yugabyte.yw.common.gflags.SpecificGFlags;
import com.yugabyte.yw.controllers.UniverseControllerTestBase;
import com.yugabyte.yw.forms.EncryptionAtRestConfig;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseTaskParams.CommunicationPorts;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.ImageBundle;
import com.yugabyte.yw.models.ImageBundleDetails;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class UniverseManagementApiControllerImpTest extends UniverseControllerTestBase {

  private UUID providerUuid;
  private UUID rootCA;
  private UUID clientRootCA;
  private String rootCAContents =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDEjCCAfqgAwIBAgIUEdzNoxkMLrZCku6H1jQ4pUgPtpQwDQYJKoZIhvcNAQEL\n"
          + "BQAwLzERMA8GA1UECgwIWXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0\n"
          + "ZURCMB4XDTIwMTIyMzA3MjU1MVoXDTIxMDEyMjA3MjU1MVowLzERMA8GA1UECgwI\n"
          + "WXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0ZURCMIIBIjANBgkqhkiG\n"
          + "9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuLPcCR1KpVSs3B2515xNAR8ntfhOM5JjLl6Y\n"
          + "WjqoyRQ4wiOg5fGQpvjsearpIntr5t6uMevpzkDMYY4U21KbIW8Vvg/kXiASKMmM\n"
          + "W4ToH3Q0NfgLUNb5zJ8df3J2JZ5CgGSpipL8VPWsuSZvrqL7V77n+ndjMTUBNf57\n"
          + "eW4VjzYq+YQhyloOlXtlfWT6WaAsoaVOlbU0GK4dE2rdeo78p2mS2wQZSBDXovpp\n"
          + "0TX4zhT6LsJaRBZe49GE4SMkxz74alK1ezRvFcrPiNKr5NOYYG9DUUqFHWg47Bmw\n"
          + "KbiZ5dLdyxgWRDbanwl2hOMfExiJhHE7pqgr8XcizCiYuUzlDwIDAQABoyYwJDAO\n"
          + "BgNVHQ8BAf8EBAMCAuQwEgYDVR0TAQH/BAgwBgEB/wIBATANBgkqhkiG9w0BAQsF\n"
          + "AAOCAQEAVI3NTJVNX4XTcVAxXXGumqCbKu9CCLhXxaJb+J8YgmMQ+s9lpmBuC1eB\n"
          + "38YFdSEG9dKXZukdQcvpgf4ryehtvpmk03s/zxNXC5237faQQqejPX5nm3o35E3I\n"
          + "ZQqN3h+mzccPaUvCaIlvYBclUAt4VrVt/W66kLFPsfUqNKVxm3B56VaZuQL1ZTwG\n"
          + "mrIYBoaVT/SmEeIX9PNjlTpprDN/oE25fOkOxwHyI9ydVFkMCpBNRv+NisQN9c+R\n"
          + "/SBXfs+07aqFgrGTte6/I4VZ/6vz2cWMwZU+TUg/u0fc0Y9RzOuJrZBV2qPAtiEP\n"
          + "YvtLjmJF//b3rsty6NFIonSVgq6Nqw==\n"
          + "-----END CERTIFICATE-----\n";
  private String clientRootCAContents =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDAjCCAeqgAwIBAgIGAXVCiJ4gMA0GCSqGSIb3DQEBCwUAMC4xFjAUBgNVBAMM\n"
          + "DXliLWFkbWluLXRlc3QxFDASBgNVBAoMC2V4YW1wbGUuY29tMB4XDTIwMTAxOTIw\n"
          + "MjQxMVoXDTIxMTAxOTIwMjQxMVowLjEWMBQGA1UEAwwNeWItYWRtaW4tdGVzdDEU\n"
          + "MBIGA1UECgwLZXhhbXBsZS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK\n"
          + "AoIBAQCw8Mk+/MK0mP67ZEKL7cGyTzggau57MzTApveLfGF1Snln/Y7wGzgbskaM\n"
          + "0udz46es9HdaC/jT+PzMAAD9MCtAe5YYSL2+lmWT+WHdeJWF4XC/AVkjqj81N6OS\n"
          + "Uxio6ww0S9cAoDmF3gZlmkRwQcsruiZ1nVyQ7l+5CerQ02JwYBIYolUu/1qMruDD\n"
          + "pLsJ9LPWXPw2JsgYWyuEB5W1xEPDl6+QLTEVCFc9oN6wJOJgf0Y6OQODBrDRxddT\n"
          + "8h0mgJ6yzmkerR8VA0bknPQFeruWNJ/4PKDO9Itk5MmmYU/olvT5zMJ79K8RSvhN\n"
          + "+3gO8N7tcswaRP7HbEUmuVTtjFDlAgMBAAGjJjAkMBIGA1UdEwEB/wQIMAYBAf8C\n"
          + "AQEwDgYDVR0PAQH/BAQDAgLkMA0GCSqGSIb3DQEBCwUAA4IBAQCB10NLTyuqSD8/\n"
          + "HmbkdmH7TM1q0V/2qfrNQW86ywVKNHlKaZp9YlAtBcY5SJK37DJJH0yKM3GYk/ee\n"
          + "4aI566aj65gQn+wte9QfqzeotfVLQ4ZlhhOjDVRqSJVUdiW7yejHQLnqexdOpPQS\n"
          + "vwi73Fz0zGNxqnNjSNtka1rmduGwP0fiU3WKtHJiPL9CQFtRKdIlskKUlXg+WulM\n"
          + "x9yw5oa6xpsbCzSoS31fxYg71KAxVvKJYumdKV3ElGU/+AK1y4loyHv/kPp+59fF\n"
          + "9Q8gq/A6vGFjoZtVuuKUlasbMocle4Y9/nVxqIxWtc+aZ8mmP//J5oVXyzPs56dM\n"
          + "E1pTE1HS\n"
          + "-----END CERTIFICATE-----\n";

  @Before
  public void setUpV2Client() throws NoSuchAlgorithmException, IOException {
    ApiClient v2ApiClient = Configuration.getDefaultApiClient();
    String basePath = String.format("http://localhost:%d/api/v2", port);
    v2ApiClient = v2ApiClient.setBasePath(basePath).addDefaultHeader("X-AUTH-TOKEN", authToken);
    Configuration.setDefaultApiClient(v2ApiClient);
    setupProvider(CloudType.aws);
    setupCerts();
  }

  private void setupProvider(CloudType cloudType) {
    Provider provider = newProvider(customer, cloudType);
    providerUuid = provider.getUuid();
    // add 3 regions with 3 zones in each region
    Region region1 = Region.create(provider, "us-west-1", "us-west-1", "yb-image-1");
    AvailabilityZone.createOrThrow(region1, "r1-az-1", "R1 AZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(region1, "r1-az-2", "R1 AZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(region1, "r1-az-3", "R1 AZ 3", "subnet-3");
    Region region2 = Region.create(provider, "us-west-2", "us-west-2", "yb-image-2");
    AvailabilityZone.createOrThrow(region2, "r2-az-1", "R2 AZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(region2, "r2-az-2", "R2 AZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(region2, "r2-az-3", "R2 AZ 3", "subnet-3");
    Region region3 = Region.create(provider, "us-west-3", "us-west-3", "yb-image-3");
    AvailabilityZone.createOrThrow(region3, "r3-az-1", "R3 AZ 1", "subnet-1");
    AvailabilityZone.createOrThrow(region3, "r3-az-2", "R3 AZ 2", "subnet-2");
    AvailabilityZone.createOrThrow(region3, "r3-az-3", "R3 AZ 3", "subnet-3");
    // add image bundle to provider
    ImageBundleDetails imageDetails = new ImageBundleDetails();
    imageDetails.setArch(Architecture.aarch64);
    imageDetails.setGlobalYbImage("yb-image-global");
    ImageBundle.create(provider, "centos-image-ami", imageDetails, true);
    // add access keys to provider (TODO?)
  }

  private void setupCerts() throws NoSuchAlgorithmException, IOException {
    createTempFile("universe_management_api_controller_test_ca.crt", rootCAContents);
    rootCA = UUID.randomUUID();
    CertificateInfo.create(
        rootCA,
        customer.getUuid(),
        "test1",
        new Date(),
        new Date(),
        "privateKey",
        TestHelper.TMP_PATH + "/universe_management_api_controller_test_ca.crt",
        CertConfigType.SelfSigned);
    createTempFile("universe_management_api_controller_test_client_ca.crt", clientRootCAContents);
    clientRootCA = UUID.randomUUID();
    CertificateInfo.create(
        clientRootCA,
        customer.getUuid(),
        "test2",
        new Date(),
        new Date(),
        "privateKey",
        TestHelper.TMP_PATH + "/universe_management_api_controller_test_client_ca.crt",
        CertConfigType.SelfSigned);
  }

  @Test
  public void testGetV2Universe() throws ApiException {
    UUID uUUID = createUniverse(customer.getId()).getUniverseUUID();
    Universe dbUniverse =
        Universe.saveDetails(
            uUUID,
            universe -> {
              // arch
              universe.getUniverseDetails().arch = PublicCloudConstants.Architecture.aarch64;
              // regionList
              List<Region> regions = Region.getByProvider(providerUuid);
              universe.getUniverseDetails().getPrimaryCluster().userIntent.regionList =
                  regions != null
                      ? regions.stream().map(r -> r.getUuid()).toList()
                      : new ArrayList<>();
              // Volumes
              universe.getUniverseDetails().getPrimaryCluster().userIntent.deviceInfo =
                  ApiUtils.getDummyDeviceInfo(2, 150);
              // instanceTags
              universe.getUniverseDetails().getPrimaryCluster().userIntent.instanceTags =
                  Map.of("tag1", "value1", "tag2", "value2");
              // instanceType
              universe.getUniverseDetails().getPrimaryCluster().userIntent.instanceType =
                  ApiUtils.UTIL_INST_TYPE;
              // GFlags
              universe.getUniverseDetails().getPrimaryCluster().userIntent.specificGFlags =
                  SpecificGFlags.construct(
                      Map.of("mflag1", "mval1", "mflag2", "mval2"),
                      Map.of("tflag1", "tval1", "tflag2", "tval2"));
              SpecificGFlags.PerProcessFlags azFlags = new SpecificGFlags.PerProcessFlags();
              azFlags.value.put(ServerType.MASTER, Map.of("mperaz1", "val1", "mperaz2", "val2"));
              azFlags.value.put(ServerType.TSERVER, Map.of("tperaz1", "v1", "tperaz2", "v2"));
              universe
                  .getUniverseDetails()
                  .getPrimaryCluster()
                  .userIntent
                  .specificGFlags
                  .setPerAZ(
                      Map.of(universe.getUniverseDetails().getPrimaryCluster().uuid, azFlags));
            });
    UniverseManagementApi api = new UniverseManagementApi();
    UniverseResp universeResp = api.getUniverse(customer.getUuid(), uUUID);
    validateUniverseSpec(universeResp.getSpec(), dbUniverse);
    validateUniverseInfo(universeResp.getInfo(), dbUniverse);
  }

  private AvailabilityZoneGFlags createAZGFlags(String azCode) {
    AvailabilityZoneGFlags azGFlags = new AvailabilityZoneGFlags();
    azGFlags.setMaster(
        Map.of(azCode + "mflag1", azCode + "mval1", azCode + "mflag2", azCode + "mval2"));
    azGFlags.setTserver(
        Map.of(azCode + "tflag1", azCode + "tval1", azCode + "tflag2", azCode + "tval2"));
    return azGFlags;
  }

  private ClusterGFlags createPrimaryClusterGFlags() {
    ClusterGFlags primaryGflags = new ClusterGFlags();
    primaryGflags.setMaster(Map.of("mflag1", "mval1", "mflag2", "mval2"));
    primaryGflags.setTserver(Map.of("tflag1", "tval1", "tflag2", "tval2"));
    Region.getByProvider(providerUuid)
        .get(0)
        .getZones()
        .forEach(
            z -> {
              primaryGflags.putAzGflagsItem(z.getUuid().toString(), createAZGFlags(z.getCode()));
            });
    return primaryGflags;
  }

  private AuditLogConfig createPrimaryAuditLogConfig() {
    AuditLogConfig auditLogConfig = new AuditLogConfig();
    return auditLogConfig;
  }

  @Test
  public void testCreateUniverseV2() throws ApiException {
    UniverseManagementApi api = new UniverseManagementApi();
    UniverseCreateSpec universeCreateSpec = new UniverseCreateSpec();
    universeCreateSpec.arch(ArchEnum.AARCH64);
    universeCreateSpec.ysql(new UniverseCreateSpecYsql().password("ysqlPassword"));
    universeCreateSpec.ycql(new UniverseCreateSpecYcql().password("ycqlPassword"));
    UniverseSpec universeSpec = new UniverseSpec();
    universeCreateSpec.spec(universeSpec);
    universeSpec.name("Test-V2-Universe");
    universeSpec.ysql(new YsqlSpec().enable(true).enableAuth(false));
    universeSpec.ycql(new YcqlSpec().enable(true).enableAuth(false));
    universeSpec.communicationPorts(
        new CommunicationPortsSpec().masterHttpPort(1234).tserverHttpPort(5678));
    EncryptionAtRestSpec ear = new EncryptionAtRestSpec();
    ear.kmsConfigUuid(UUID.randomUUID()).opType(OpTypeEnum.ENABLE).type(TypeEnum.CMK);
    universeSpec.encryptionAtRestSpec(ear);
    EncryptionInTransitSpec eit = new EncryptionInTransitSpec();
    eit.enableNodeToNodeEncrypt(true)
        .enableClientToNodeEncrypt(true)
        .rootCa(rootCA)
        .clientRootCa(clientRootCA);
    universeSpec.encryptionInTransitSpec(eit);
    ClusterSpec primaryClusterSpec = new ClusterSpec();
    primaryClusterSpec.setClusterType(ClusterTypeEnum.PRIMARY);
    primaryClusterSpec.setNumNodes(6);
    primaryClusterSpec.setInstanceType(ApiUtils.UTIL_INST_TYPE);
    primaryClusterSpec.setReplicationFactor(5);
    primaryClusterSpec.setYbSoftwareVersion("2.20.0.0-b123");
    primaryClusterSpec.setUseTimeSync(true);
    primaryClusterSpec.setUseSpotInstance(true);
    primaryClusterSpec.setUseSystemd(true);
    primaryClusterSpec.setInstanceTags(Map.of("itag1", "ival1", "itag2", "ival2"));
    ClusterProviderSpec providerSpec = new ClusterProviderSpec();
    providerSpec.setProvider(providerUuid);
    providerSpec.setRegionList(List.of(Region.getByProvider(providerUuid).get(0).getUuid()));
    providerSpec.setImageBundleUuid(ImageBundle.getAll(providerUuid).get(0).getUuid());
    providerSpec.setAccessKeyCode(ApiUtils.DEFAULT_ACCESS_KEY_CODE);
    primaryClusterSpec.setProviderSpec(providerSpec);
    primaryClusterSpec.setStorageSpec(
        new ClusterStorageSpec().volumeSize(54321).numVolumes(2).storageType(StorageTypeEnum.GP2));
    primaryClusterSpec.setNetworkingSpec(
        new ClusterNetworkingSpec()
            .assignPublicIp(true)
            .assignStaticPublicIp(true)
            .enableIpv6(false)
            .enableLb(true)
            .enableExposingService(EnableExposingServiceEnum.EXPOSED));
    primaryClusterSpec.setGflags(createPrimaryClusterGFlags());
    primaryClusterSpec.setAuditLogConfig(createPrimaryAuditLogConfig());
    universeSpec.addClustersItem(primaryClusterSpec);
    UUID fakeTaskUUID = FakeDBApplication.buildTaskInfo(null, TaskType.CreateUniverse);
    when(mockCommissioner.submit(any(TaskType.class), any(UniverseDefinitionTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    YBPTask createTask = api.createUniverse(customer.getUuid(), universeCreateSpec);
    UUID universeUUID = createTask.getResourceUuid();
    Universe dbUniv = Universe.getOrBadRequest(universeUUID, customer);

    // validate that the Universe created in the DB matches properties specified in the createSpec
    validateUniverseCreateSpec(universeCreateSpec, dbUniv);
  }

  private void validateUniverseCreateSpec(UniverseCreateSpec v2Univ, Universe dbUniv) {
    UniverseDefinitionTaskParams dbUnivDetails = dbUniv.getUniverseDetails();
    assertThat(v2Univ.getArch().getValue(), is(dbUnivDetails.arch.name()));
    validateUniverseSpec(v2Univ.getSpec(), dbUniv);
  }

  private void validateUniverseSpec(UniverseSpec v2UnivSpec, Universe dbUniv) {
    assertThat(v2UnivSpec.getName(), is(dbUniv.getName()));
    if (v2UnivSpec.getOverridePrebuiltAmiDbVersion() == null) {
      assertThat(dbUniv.getUniverseDetails().overridePrebuiltAmiDBVersion, is(false));
    } else {
      assertThat(
          v2UnivSpec.getOverridePrebuiltAmiDbVersion(),
          is(dbUniv.getUniverseDetails().overridePrebuiltAmiDBVersion));
    }
    if (v2UnivSpec.getRemotePackagePath() == null) {
      assertThat(dbUniv.getUniverseDetails().remotePackagePath, is(emptyString()));
    } else {
      assertThat(
          v2UnivSpec.getRemotePackagePath(), is(dbUniv.getUniverseDetails().remotePackagePath));
    }
    validateCommunicationPorts(
        v2UnivSpec.getCommunicationPorts(), dbUniv.getUniverseDetails().communicationPorts);
    validateEncryptionAtRest(
        v2UnivSpec.getEncryptionAtRestSpec(), dbUniv.getUniverseDetails().encryptionAtRestConfig);
    validateEncryptionInTransit(
        v2UnivSpec.getEncryptionInTransitSpec(), dbUniv.getUniverseDetails());
    validateYsqlSpec(v2UnivSpec.getYsql(), dbUniv.getUniverseDetails());
    validateYcqlSpec(v2UnivSpec.getYcql(), dbUniv.getUniverseDetails());
    validateClusters(v2UnivSpec.getClusters(), dbUniv.getUniverseDetails().clusters);
  }

  private void validateCommunicationPorts(CommunicationPortsSpec v2CP, CommunicationPorts dbCP) {
    if (v2CP.getMasterHttpPort() == null) {
      assertThat(dbCP.masterHttpPort, is(7000));
    } else {
      assertThat(dbCP.masterHttpPort, is(v2CP.getMasterHttpPort()));
    }
    if (v2CP.getMasterRpcPort() == null) {
      assertThat(dbCP.masterRpcPort, is(7100));
    } else {
      assertThat(dbCP.masterRpcPort, is(v2CP.getMasterRpcPort()));
    }
    if (v2CP.getNodeExporterPort() == null) {
      assertThat(dbCP.nodeExporterPort, is(9300));
    } else {
      assertThat(dbCP.nodeExporterPort, is(v2CP.getNodeExporterPort()));
    }
    if (v2CP.getOtelCollectorMetricsPort() == null) {
      // default is coming from Provider runtime config yb.universe.otel_collector_metrics_port
      assertThat(dbCP.otelCollectorMetricsPort, is(0));
    } else {
      assertThat(dbCP.otelCollectorMetricsPort, is(v2CP.getOtelCollectorMetricsPort()));
    }
    if (v2CP.getRedisServerHttpPort() == null) {
      assertThat(dbCP.redisServerHttpPort, is(11000));
    } else {
      assertThat(dbCP.redisServerHttpPort, is(v2CP.getRedisServerHttpPort()));
    }
    if (v2CP.getRedisServerRpcPort() == null) {
      assertThat(dbCP.redisServerRpcPort, is(6379));
    } else {
      assertThat(dbCP.redisServerRpcPort, is(v2CP.getRedisServerRpcPort()));
    }
    if (v2CP.getTserverHttpPort() == null) {
      assertThat(dbCP.tserverHttpPort, is(9000));
    } else {
      assertThat(dbCP.tserverHttpPort, is(v2CP.getTserverHttpPort()));
    }
    if (v2CP.getTserverRpcPort() == null) {
      assertThat(dbCP.tserverRpcPort, is(9100));
    } else {
      assertThat(dbCP.tserverRpcPort, is(v2CP.getTserverRpcPort()));
    }
    if (v2CP.getYbControllerHttpPort() == null) {
      assertThat(dbCP.ybControllerHttpPort, is(14000));
    } else {
      assertThat(dbCP.ybControllerHttpPort, is(v2CP.getYbControllerHttpPort()));
    }
    if (v2CP.getYbControllerRpcPort() == null) {
      assertThat(dbCP.ybControllerrRpcPort, is(18018));
    } else {
      assertThat(dbCP.ybControllerrRpcPort, is(v2CP.getYbControllerRpcPort()));
    }
    if (v2CP.getYqlServerHttpPort() == null) {
      assertThat(dbCP.yqlServerHttpPort, is(12000));
    } else {
      assertThat(dbCP.yqlServerHttpPort, is(v2CP.getYqlServerHttpPort()));
    }
    if (v2CP.getYqlServerRpcPort() == null) {
      assertThat(dbCP.yqlServerRpcPort, is(9042));
    } else {
      assertThat(dbCP.yqlServerRpcPort, is(v2CP.getYqlServerRpcPort()));
    }
    if (v2CP.getYsqlServerHttpPort() == null) {
      assertThat(dbCP.ysqlServerHttpPort, is(13000));
    } else {
      assertThat(dbCP.ysqlServerHttpPort, is(v2CP.getYsqlServerHttpPort()));
    }
    if (v2CP.getYsqlServerRpcPort() == null) {
      assertThat(dbCP.ysqlServerRpcPort, is(5433));
    } else {
      assertThat(dbCP.ysqlServerRpcPort, is(v2CP.getYsqlServerRpcPort()));
    }
  }

  private void validateEncryptionAtRest(EncryptionAtRestSpec v2Enc, EncryptionAtRestConfig dbEnc) {
    assertThat(v2Enc.getKmsConfigUuid(), is(dbEnc.kmsConfigUUID));
    assertThat(v2Enc.getOpType().getValue(), is(dbEnc.opType.name()));
    assertThat(v2Enc.getType().getValue(), is(dbEnc.type.name()));
    assertThat(dbEnc.encryptionAtRestEnabled, is(v2Enc.getOpType().equals(OpTypeEnum.ENABLE)));
  }

  private void validateEncryptionInTransit(
      EncryptionInTransitSpec v2EIT, UniverseDefinitionTaskParams dbUniv) {
    assertThat(
        v2EIT.getEnableNodeToNodeEncrypt(),
        is(dbUniv.getPrimaryCluster().userIntent.enableNodeToNodeEncrypt));
    assertThat(
        v2EIT.getEnableClientToNodeEncrypt(),
        is(dbUniv.getPrimaryCluster().userIntent.enableClientToNodeEncrypt));
    assertThat(v2EIT.getRootCa(), is(dbUniv.rootCA));
    assertThat(v2EIT.getClientRootCa(), is(dbUniv.getClientRootCA()));
    if (v2EIT.getRootCa() != null) {
      assertThat(
          dbUniv.rootAndClientRootCASame, is(v2EIT.getRootCa().equals(v2EIT.getClientRootCa())));
    } else {
      assertThat(dbUniv.rootAndClientRootCASame, is(true));
    }
  }

  private void validateYsqlSpec(YsqlSpec ysql, UniverseDefinitionTaskParams dbUniv) {
    if (ysql != null) {
      assertThat(ysql.getEnable(), is(dbUniv.getPrimaryCluster().userIntent.enableYSQL));
      assertThat(ysql.getEnableAuth(), is(dbUniv.getPrimaryCluster().userIntent.enableYSQLAuth));
    } else {
      assertThat(dbUniv.getPrimaryCluster().userIntent.enableYSQL, is(false));
      assertThat(dbUniv.getPrimaryCluster().userIntent.enableYSQLAuth, is(false));
    }
  }

  private void validateYcqlSpec(YcqlSpec ycql, UniverseDefinitionTaskParams dbUniv) {
    if (ycql != null) {
      assertThat(ycql.getEnable(), is(dbUniv.getPrimaryCluster().userIntent.enableYCQL));
      assertThat(ycql.getEnableAuth(), is(dbUniv.getPrimaryCluster().userIntent.enableYCQLAuth));
    } else {
      assertThat(dbUniv.getPrimaryCluster().userIntent.enableYCQL, is(false));
      assertThat(dbUniv.getPrimaryCluster().userIntent.enableYCQLAuth, is(false));
    }
  }

  private void validateClusters(List<ClusterSpec> v2Clusters, List<Cluster> dbClusters) {
    assertThat(v2Clusters.size(), is(dbClusters.size()));
    for (int i = 0; i < v2Clusters.size(); i++) {
      validateCluster(v2Clusters.get(i), dbClusters.get(i));
    }
  }

  private void validateCluster(ClusterSpec v2Cluster, Cluster dbCluster) {
    assertThat(v2Cluster.getClusterType().getValue(), is(dbCluster.clusterType.name()));
    assertThat(v2Cluster.getNumNodes(), is(dbCluster.userIntent.numNodes));
    assertThat(v2Cluster.getReplicationFactor(), is(dbCluster.userIntent.replicationFactor));
    assertThat(v2Cluster.getInstanceType(), is(dbCluster.userIntent.instanceType));
    assertThat(v2Cluster.getUseSpotInstance(), is(dbCluster.userIntent.useSpotInstance));
    assertThat(v2Cluster.getUseSystemd(), is(dbCluster.userIntent.useSystemd));
    assertThat(v2Cluster.getUseTimeSync(), is(dbCluster.userIntent.useTimeSync));
    assertThat(v2Cluster.getYbSoftwareVersion(), is(dbCluster.userIntent.ybSoftwareVersion));
    validateProviderSpec(v2Cluster.getProviderSpec(), dbCluster);
    validateStorageSpec(v2Cluster.getStorageSpec(), dbCluster);
    validateNetworkingSpec(v2Cluster.getNetworkingSpec(), dbCluster);
    validateGFlags(v2Cluster.getGflags(), dbCluster.userIntent.specificGFlags);
    validateInstanceTags(v2Cluster.getInstanceTags(), dbCluster.userIntent.instanceTags);
    validateAuditLogConfig(v2Cluster.getAuditLogConfig(), dbCluster.userIntent.auditLogConfig);
  }

  private void validateProviderSpec(ClusterProviderSpec v2ProviderSpec, Cluster dbCluster) {
    assertThat(v2ProviderSpec.getProvider(), is(UUID.fromString(dbCluster.userIntent.provider)));
    assertThat(v2ProviderSpec.getImageBundleUuid(), is(dbCluster.userIntent.imageBundleUUID));
    assertThat(v2ProviderSpec.getPreferredRegion(), is(dbCluster.userIntent.preferredRegion));
    assertThat(v2ProviderSpec.getAccessKeyCode(), is(dbCluster.userIntent.accessKeyCode));
    assertThat(
        v2ProviderSpec.getRegionList(),
        containsInAnyOrder(dbCluster.userIntent.regionList.toArray()));
  }

  private void validateStorageSpec(ClusterStorageSpec v2StorageSpec, Cluster dbCluster) {
    assertThat(v2StorageSpec.getNumVolumes(), is(dbCluster.userIntent.deviceInfo.numVolumes));
    assertThat(v2StorageSpec.getVolumeSize(), is(dbCluster.userIntent.deviceInfo.volumeSize));
    assertThat(v2StorageSpec.getDiskIops(), is(dbCluster.userIntent.deviceInfo.diskIops));
    assertThat(v2StorageSpec.getMountPoints(), is(dbCluster.userIntent.deviceInfo.mountPoints));
    if (v2StorageSpec.getStorageClass() == null) {
      assertThat(dbCluster.userIntent.deviceInfo.storageClass, is(emptyString()));
    } else {
      assertThat(v2StorageSpec.getStorageClass(), is(dbCluster.userIntent.deviceInfo.storageClass));
    }
    assertThat(
        v2StorageSpec.getStorageType().getValue(),
        is(dbCluster.userIntent.deviceInfo.storageType.name()));
    assertThat(v2StorageSpec.getThroughput(), is(dbCluster.userIntent.deviceInfo.throughput));
  }

  private void validateNetworkingSpec(ClusterNetworkingSpec v2NetworkingSpec, Cluster dbCluster) {
    assertThat(v2NetworkingSpec.getAssignPublicIp(), is(dbCluster.userIntent.assignPublicIP));
    assertThat(
        v2NetworkingSpec.getAssignStaticPublicIp(), is(dbCluster.userIntent.assignStaticPublicIP));
    assertThat(
        v2NetworkingSpec.getEnableExposingService().getValue(),
        is(dbCluster.userIntent.enableExposingService.name()));
    if (v2NetworkingSpec.getEnableIpv6() == null) {
      assertThat(dbCluster.userIntent.enableIPV6, is(false));
    } else {
      assertThat(v2NetworkingSpec.getEnableIpv6(), is(dbCluster.userIntent.enableIPV6));
    }
    assertThat(v2NetworkingSpec.getEnableLb(), is(dbCluster.userIntent.enableLB));
  }

  private void validateInstanceTags(
      Map<String, String> v2InstanceTags, Map<String, String> dbInstanceTags) {
    assertThat(v2InstanceTags.size(), is(dbInstanceTags.size()));
    v2InstanceTags
        .entrySet()
        .forEach(e -> assertThat(dbInstanceTags, hasEntry(e.getKey(), e.getValue())));
  }

  private void validateGFlags(ClusterGFlags v2GFlags, SpecificGFlags dbGFlags) {
    v2GFlags
        .getMaster()
        .entrySet()
        .forEach(
            e ->
                assertThat(
                    dbGFlags.getPerProcessFlags().value.get(ServerType.MASTER),
                    hasEntry(e.getKey(), e.getValue())));
    v2GFlags
        .getTserver()
        .entrySet()
        .forEach(
            e ->
                assertThat(
                    dbGFlags.getPerProcessFlags().value.get(ServerType.TSERVER),
                    hasEntry(e.getKey(), e.getValue())));
    v2GFlags
        .getAzGflags()
        .entrySet()
        .forEach(
            e -> {
              UUID azUuid = UUID.fromString(e.getKey());
              AvailabilityZoneGFlags azGFlags = e.getValue();
              azGFlags
                  .getMaster()
                  .entrySet()
                  .forEach(
                      m ->
                          assertThat(
                              dbGFlags.getPerAZ().get(azUuid).value.get(ServerType.MASTER),
                              hasEntry(m.getKey(), m.getValue())));
              azGFlags
                  .getTserver()
                  .entrySet()
                  .forEach(
                      t ->
                          assertThat(
                              dbGFlags.getPerAZ().get(azUuid).value.get(ServerType.TSERVER),
                              hasEntry(t.getKey(), t.getValue())));
            });
  }

  private void validateAuditLogConfig(
      AuditLogConfig v2AuditLogConfig,
      com.yugabyte.yw.models.helpers.audit.AuditLogConfig dbAuditLogConfig) {
    if (v2AuditLogConfig == null) {
      assertThat(dbAuditLogConfig, is(nullValue()));
      return;
    }
    if (v2AuditLogConfig.getExportActive() == null) {
      assertThat(dbAuditLogConfig.isExportActive(), is(true));
    } else {
      assertThat(v2AuditLogConfig.getExportActive(), is(dbAuditLogConfig.isExportActive()));
    }
    assertThat(
        v2AuditLogConfig.getUniverseLogsExporterConfig().size(),
        is(dbAuditLogConfig.getUniverseLogsExporterConfig().size()));
    for (int i = 0; i < v2AuditLogConfig.getUniverseLogsExporterConfig().size(); i++) {
      validateUniverseLogsExportedConfig(
          v2AuditLogConfig.getUniverseLogsExporterConfig().get(i),
          dbAuditLogConfig.getUniverseLogsExporterConfig().get(i));
    }
    validateYsqlAuditConfig(
        v2AuditLogConfig.getYsqlAuditConfig(), dbAuditLogConfig.getYsqlAuditConfig());
    validateYcqlAuditConfig(
        v2AuditLogConfig.getYcqlAuditConfig(), dbAuditLogConfig.getYcqlAuditConfig());
  }

  private void validateUniverseLogsExportedConfig(
      UniverseLogsExporterConfig v2UniverseLogsExporterConfig,
      com.yugabyte.yw.models.helpers.audit.UniverseLogsExporterConfig
          dbUniverseLogsExporterConfig) {
    if (v2UniverseLogsExporterConfig == null) {
      assertThat(dbUniverseLogsExporterConfig, is(nullValue()));
      return;
    }
    assertThat(
        v2UniverseLogsExporterConfig.getExporterUuid(),
        is(dbUniverseLogsExporterConfig.getExporterUuid()));
    v2UniverseLogsExporterConfig
        .getAdditionalTags()
        .entrySet()
        .forEach(
            e ->
                assertThat(
                    dbUniverseLogsExporterConfig.getAdditionalTags(),
                    hasEntry(e.getKey(), e.getValue())));
  }

  private void validateYsqlAuditConfig(
      YSQLAuditConfig v2YsqlAuditConfig,
      com.yugabyte.yw.models.helpers.audit.YSQLAuditConfig dbYsqlAuditConfig) {
    if (v2YsqlAuditConfig == null) {
      assertThat(dbYsqlAuditConfig, is(nullValue()));
      return;
    }
    Set<String> v2ClassNames =
        v2YsqlAuditConfig.getClasses().stream().map(c -> c.getValue()).collect(Collectors.toSet());
    Set<String> dbClasseNames =
        dbYsqlAuditConfig.getClasses().stream().map(c -> c.name()).collect(Collectors.toSet());
    assertThat(v2ClassNames, containsInAnyOrder(dbClasseNames.toArray()));
    assertThat(v2YsqlAuditConfig.getEnabled(), is(dbYsqlAuditConfig.isEnabled()));
    assertThat(v2YsqlAuditConfig.getLogCatalog(), is(dbYsqlAuditConfig.isLogCatalog()));
    assertThat(v2YsqlAuditConfig.getLogClient(), is(dbYsqlAuditConfig.isLogClient()));
    if (v2YsqlAuditConfig.getLogLevel() == null) {
      assertThat(dbYsqlAuditConfig.getLogLevel(), is(nullValue()));
    } else {
      assertThat(
          v2YsqlAuditConfig.getLogLevel().getValue(), is(dbYsqlAuditConfig.getLogLevel().name()));
    }
    assertThat(v2YsqlAuditConfig.getLogParameter(), is(dbYsqlAuditConfig.isLogParameter()));
    assertThat(
        v2YsqlAuditConfig.getLogParameterMaxSize(), is(dbYsqlAuditConfig.getLogParameterMaxSize()));
    assertThat(v2YsqlAuditConfig.getLogRelation(), is(dbYsqlAuditConfig.isLogRelation()));
    assertThat(v2YsqlAuditConfig.getLogRow(), is(dbYsqlAuditConfig.isLogRow()));
    assertThat(v2YsqlAuditConfig.getLogStatement(), is(dbYsqlAuditConfig.isLogStatement()));
    assertThat(v2YsqlAuditConfig.getLogStatementOnce(), is(dbYsqlAuditConfig.isLogStatementOnce()));
  }

  private void validateYcqlAuditConfig(
      YCQLAuditConfig v2YcqlAuditConfig,
      com.yugabyte.yw.models.helpers.audit.YCQLAuditConfig dbYcqlAuditConfig) {
    if (v2YcqlAuditConfig == null) {
      assertThat(dbYcqlAuditConfig, is(nullValue()));
      return;
    }
    assertThat(v2YcqlAuditConfig.getEnabled(), is(dbYcqlAuditConfig.isEnabled()));
    Set<String> v2ExcludedCategories =
        v2YcqlAuditConfig.getExcludedCategories() != null
            ? v2YcqlAuditConfig.getExcludedCategories().stream()
                .map(e -> e.getValue())
                .collect(Collectors.toSet())
            : Set.of();
    Set<String> dbExcludedCategories =
        dbYcqlAuditConfig.getExcludedCategories() != null
            ? dbYcqlAuditConfig.getExcludedCategories().stream()
                .map(e -> e.name())
                .collect(Collectors.toSet())
            : Set.of();
    assertThat(v2ExcludedCategories, containsInAnyOrder(dbExcludedCategories.toArray()));
    if (v2YcqlAuditConfig.getExcludedKeyspaces() == null) {
      assertThat(dbYcqlAuditConfig.getExcludedKeyspaces(), is(nullValue()));
    } else {
      assertThat(
          v2YcqlAuditConfig.getExcludedKeyspaces(),
          containsInAnyOrder(dbYcqlAuditConfig.getExcludedKeyspaces().toArray()));
    }
    if (v2YcqlAuditConfig.getExcludedUsers() == null) {
      assertThat(dbYcqlAuditConfig.getExcludedUsers(), is(nullValue()));
    } else {
      assertThat(
          v2YcqlAuditConfig.getExcludedUsers(),
          containsInAnyOrder(dbYcqlAuditConfig.getExcludedUsers().toArray()));
    }
    Set<String> v2IncludedCategories =
        v2YcqlAuditConfig.getIncludedCategories() != null
            ? v2YcqlAuditConfig.getIncludedCategories().stream()
                .map(e -> e.getValue())
                .collect(Collectors.toSet())
            : Set.of();
    Set<String> dbIncludedCategories =
        dbYcqlAuditConfig.getIncludedCategories() != null
            ? dbYcqlAuditConfig.getIncludedCategories().stream()
                .map(e -> e.name())
                .collect(Collectors.toSet())
            : Set.of();
    assertThat(v2IncludedCategories, containsInAnyOrder(dbIncludedCategories.toArray()));
    if (v2YcqlAuditConfig.getIncludedKeyspaces() == null) {
      assertThat(dbYcqlAuditConfig.getIncludedKeyspaces(), is(nullValue()));
    } else {
      assertThat(
          v2YcqlAuditConfig.getIncludedKeyspaces(),
          containsInAnyOrder(dbYcqlAuditConfig.getIncludedKeyspaces().toArray()));
    }
    if (v2YcqlAuditConfig.getIncludedUsers() == null) {
      assertThat(dbYcqlAuditConfig.getIncludedUsers(), is(nullValue()));
    } else {
      assertThat(
          v2YcqlAuditConfig.getIncludedUsers(),
          containsInAnyOrder(dbYcqlAuditConfig.getIncludedUsers().toArray()));
    }
    if (v2YcqlAuditConfig.getLogLevel() == null) {
      assertThat(dbYcqlAuditConfig.getLogLevel(), is(nullValue()));
    } else {
      assertThat(
          v2YcqlAuditConfig.getLogLevel().getValue(), is(dbYcqlAuditConfig.getLogLevel().name()));
    }
  }

  private void validateUniverseInfo(UniverseInfo v2UnivInfo, Universe dbUniverse) {
    UniverseDefinitionTaskParams dbUniv = dbUniverse.getUniverseDetails();
    if (v2UnivInfo.getArch() == null) {
      // default image bundle arch used in cloud provider in this test is aarch64
      assertThat(dbUniv.arch, is(PublicCloudConstants.Architecture.aarch64));
    } else {
      assertThat(v2UnivInfo.getArch().getValue(), is(dbUniv.arch.name()));
    }
    validateClustersInfo(v2UnivInfo.getClusters(), dbUniverse);
  }

  private void validateClustersInfo(List<ClusterInfo> v2Clusters, Universe dbUniverse) {
    UniverseDefinitionTaskParams dbUniv = dbUniverse.getUniverseDetails();
    assertThat(v2Clusters.size(), is(dbUniv.clusters.size()));
    v2Clusters.forEach(
        v2Cluster -> {
          Cluster dbCluster =
              dbUniv.clusters.stream()
                  .filter(dbCls -> dbCls.uuid.equals(v2Cluster.getUuid()))
                  .findFirst()
                  .orElseThrow();
          validateClusterInfo(v2Cluster, dbCluster);
        });
  }

  private void validateClusterInfo(ClusterInfo v2Cluster, Cluster dbCluster) {
    assertThat(v2Cluster.getUuid(), is(dbCluster.uuid));
    assertThat(v2Cluster.getSpotPrice(), is(dbCluster.userIntent.spotPrice));
    validatePlacementInfo(v2Cluster.getPlacementInfo(), dbCluster.placementInfo);
  }

  private void validatePlacementInfo(
      PlacementInfo v2PI, com.yugabyte.yw.models.helpers.PlacementInfo dbPI) {
    if (v2PI == null) {
      assertThat(dbPI, is(nullValue()));
      return;
    }
    if (v2PI.getCloudList() == null) {
      assertThat(dbPI.cloudList, is(nullValue()));
      return;
    }
    assertThat(v2PI.getCloudList().size(), is(dbPI.cloudList.size()));
    for (PlacementCloud v2Cloud : v2PI.getCloudList()) {
      // find corresponding cloud in db cloud list
      com.yugabyte.yw.models.helpers.PlacementInfo.PlacementCloud dbCloud =
          dbPI.cloudList.stream()
              .filter(c -> c.uuid.equals(v2Cloud.getUuid()))
              .findFirst()
              .orElseThrow();
      assertThat(v2Cloud.getCode(), is(dbCloud.code));
      assertThat(v2Cloud.getDefaultRegion(), is(dbCloud.defaultRegion));
      verifyPlacementRegion(v2Cloud.getRegionList(), dbCloud.regionList);
    }
  }

  private void verifyPlacementRegion(
      List<PlacementRegion> v2RegionList,
      List<com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion> dbRegionList) {
    if (v2RegionList == null) {
      assertThat(dbRegionList, is(nullValue()));
      return;
    }
    for (PlacementRegion v2Region : v2RegionList) {
      // find corresponding region in db region list
      com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion dbRegion =
          dbRegionList.stream()
              .filter(r -> r.uuid.equals(v2Region.getUuid()))
              .findFirst()
              .orElseThrow();
      assertThat(v2Region.getCode(), is(dbRegion.code));
      assertThat(v2Region.getName(), is(dbRegion.name));
      assertThat(v2Region.getLbFqdn(), is(dbRegion.lbFQDN));
      verifyPlacementZone(v2Region.getAzList(), dbRegion.azList);
    }
  }

  private void verifyPlacementZone(
      List<PlacementAZ> v2AzList,
      List<com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ> dbAzList) {
    if (v2AzList == null) {
      assertThat(dbAzList, is(nullValue()));
      return;
    }
    for (PlacementAZ v2Az : v2AzList) {
      // find corresponding az in db az list
      com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ dbAz =
          dbAzList.stream().filter(a -> a.uuid.equals(v2Az.getUuid())).findFirst().orElseThrow();
      assertThat(v2Az.getIsAffinitized(), is(dbAz.isAffinitized));
      assertThat(v2Az.getLbName(), is(dbAz.lbName));
      assertThat(v2Az.getName(), is(dbAz.name));
      assertThat(v2Az.getNumNodesInAz(), is(dbAz.numNodesInAZ));
      assertThat(v2Az.getReplicationFactor(), is(dbAz.replicationFactor));
      assertThat(v2Az.getSecondarySubnet(), is(dbAz.secondarySubnet));
      assertThat(v2Az.getSubnet(), is(dbAz.subnet));
    }
  }
}
