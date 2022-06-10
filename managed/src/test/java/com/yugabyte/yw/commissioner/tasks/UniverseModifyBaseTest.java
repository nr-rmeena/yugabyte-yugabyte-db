package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.NodeManager;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.forms.NodeInstanceFormData;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.NodeInstance;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.yb.client.YBClient;

public abstract class UniverseModifyBaseTest extends CommissionerBaseTest {
  protected static final String AZ_CODE = "az-1";

  protected Universe onPremUniverse;
  protected Universe defaultUniverse;

  protected ShellResponse dummyShellResponse;
  protected ShellResponse preflightResponse;
  protected ShellResponse listResponse;

  protected YBClient mockClient;

  @Override
  @Before
  public void setUp() {
    super.setUp();
    defaultUniverse = createUniverseForProvider("Test Universe", defaultProvider);
    onPremUniverse = createUniverseForProvider("Test onPrem Universe", onPremProvider);
    dummyShellResponse = new ShellResponse();
    dummyShellResponse.message = "true";
    preflightResponse = new ShellResponse();
    preflightResponse.message = "{\"test\": true}";
    listResponse = new ShellResponse();
    when(mockNodeManager.nodeCommand(any(), any()))
        .then(
            invocation -> {
              if (invocation.getArgument(0).equals(NodeManager.NodeCommandType.Precheck)) {
                NodeTaskParams params = invocation.getArgument(1);
                NodeInstance.getByName(params.nodeName); // verify node is picked
                return preflightResponse;
              }
              if (invocation.getArgument(0).equals(NodeManager.NodeCommandType.List)) {
                NodeTaskParams params = invocation.getArgument(1);
                if (params.nodeUuid == null) {
                  listResponse.message = "{\"universe_uuid\":\"" + params.universeUUID + "\"}";
                } else {
                  listResponse.message =
                      "{\"universe_uuid\":\""
                          + params.universeUUID
                          + "\", "
                          + "\"node_uuid\": \""
                          + params.nodeUuid
                          + "\"}";
                }
                return listResponse;
              }
              return dummyShellResponse;
            });
    mockClient = mock(YBClient.class);
    when(mockClient.waitForServer(any(), anyLong())).thenReturn(true);
    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
  }

  protected Universe createUniverseForProvider(String universeName, Provider provider) {
    Region region = Region.create(provider, "region-1", "Region 1", "yb-image-1");
    AvailabilityZone zone = AvailabilityZone.createOrThrow(region, AZ_CODE, "AZ 1", "subnet-1");
    // create default universe
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = "yb-version";
    userIntent.accessKeyCode = "demo-access";
    userIntent.replicationFactor = 3;
    userIntent.regionList = ImmutableList.of(region.uuid);
    userIntent.instanceType = ApiUtils.UTIL_INST_TYPE;
    Common.CloudType providerType = Common.CloudType.valueOf(provider.code);
    userIntent.providerType = providerType;
    userIntent.provider = provider.uuid.toString();
    userIntent.universeName = universeName;
    if (providerType == Common.CloudType.onprem) {
      createOnpremInstance(zone);
      createOnpremInstance(zone);
      createOnpremInstance(zone);
    }
    Map<String, String> gflags = new HashMap<>();
    gflags.put("foo", "bar");
    userIntent.masterGFlags = gflags;
    userIntent.tserverGFlags = gflags;
    Universe result = createUniverse(universeName, defaultCustomer.getCustomerId(), providerType);
    result =
        Universe.saveDetails(
            result.universeUUID, ApiUtils.mockUniverseUpdater(userIntent, true /* setMasters */));
    if (providerType == Common.CloudType.onprem) {
      Universe.saveDetails(
          result.universeUUID,
          u -> {
            String instanceType = u.getNodes().iterator().next().cloudInfo.instance_type;
            Map<UUID, List<String>> onpremAzToNodes = new HashMap<>();
            for (NodeDetails node : u.getNodes()) {
              List<String> nodeNames = onpremAzToNodes.getOrDefault(node.azUuid, new ArrayList<>());
              nodeNames.add(node.nodeName);
              onpremAzToNodes.put(node.azUuid, nodeNames);
            }
            Map<String, NodeInstance> nodeMap =
                NodeInstance.pickNodes(onpremAzToNodes, instanceType);
            for (NodeDetails node : u.getNodes()) {
              NodeInstance nodeInstance = nodeMap.get(node.nodeName);
              if (nodeInstance != null) {
                node.nodeUuid = nodeInstance.getNodeUuid();
              }
            }
          },
          false);
    }

    return result;
  }

  protected void createOnpremInstance(AvailabilityZone zone) {
    NodeInstanceFormData.NodeInstanceData nodeData = new NodeInstanceFormData.NodeInstanceData();
    nodeData.ip = "fake_ip_" + zone.region.code;
    nodeData.region = zone.region.code;
    nodeData.zone = zone.code;
    nodeData.instanceType = ApiUtils.UTIL_INST_TYPE;
    NodeInstance node = NodeInstance.create(zone.uuid, nodeData);
    node.save();
  }
}
