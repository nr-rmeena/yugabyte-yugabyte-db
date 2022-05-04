// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.SubTaskGroup;
import com.yugabyte.yw.commissioner.UpgradeTaskBase;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleConfigureServers;
import com.yugabyte.yw.commissioner.tasks.subtasks.UniverseSetTlsParams;
import com.yugabyte.yw.forms.TlsToggleParams;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeOption;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeTaskSubType;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeTaskType;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;

public class TlsToggle extends UpgradeTaskBase {

  @Inject
  protected TlsToggle(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected TlsToggleParams taskParams() {
    return (TlsToggleParams) taskParams;
  }

  @Override
  public SubTaskGroupType getTaskSubGroupType() {
    return SubTaskGroupType.ToggleTls;
  }

  @Override
  public NodeState getNodeState() {
    return NodeState.ToggleTls;
  }

  @Override
  public void run() {
    runUpgrade(
        () -> {
          Pair<List<NodeDetails>, List<NodeDetails>> nodes = fetchNodes(taskParams().upgradeOption);
          // Verify the request params and fail if invalid
          verifyParams();
          // Copy any new certs to all nodes
          createCopyCertTasks(nodes.getRight());
          // Round 1 gflags upgrade
          createRound1GFlagUpdateTasks(nodes);
          // Update TLS related params in universe details
          createUniverseSetTlsParamsTask();
          // Round 2 gflags upgrade
          createRound2GFlagUpdateTasks(nodes);
        });
  }

  private void verifyParams() {
    taskParams().verifyParams(getUniverse());

    if ((taskParams().enableNodeToNodeEncrypt || taskParams().enableClientToNodeEncrypt)
        && taskParams().rootCA == null) {
      throw new IllegalArgumentException("Root certificate cannot be null when enabling TLS");
    }
  }

  private void createRound1GFlagUpdateTasks(Pair<List<NodeDetails>, List<NodeDetails>> nodes) {
    if (getNodeToNodeChange() < 0) {
      // Setting allow_insecure to true can be done in non-restart way
      createNonRestartUpgradeTaskFlow(
          (List<NodeDetails> nodeList, ServerType processType) -> {
            createGFlagUpdateTasks(1, nodeList, processType);
            Map<String, String> gflags = new HashMap<>();
            gflags.put("allow_insecure_connections", "true");
            createSetFlagInMemoryTasks(nodeList, processType, true, gflags, false)
                .setSubTaskGroupType(getTaskSubGroupType());
          },
          nodes);
    } else {
      if (taskParams().upgradeOption == UpgradeOption.ROLLING_UPGRADE) {
        createRollingUpgradeTaskFlow(
            (List<NodeDetails> nodeList, ServerType processType) ->
                createGFlagUpdateTasks(1, nodeList, processType),
            nodes,
            true);
      } else if (taskParams().upgradeOption == UpgradeOption.NON_ROLLING_UPGRADE) {
        createNonRollingUpgradeTaskFlow(
            (List<NodeDetails> nodeList, ServerType processType) ->
                createGFlagUpdateTasks(1, nodeList, processType),
            nodes,
            true);
      }
    }
  }

  private void createRound2GFlagUpdateTasks(Pair<List<NodeDetails>, List<NodeDetails>> nodes) {
    // Second round upgrade not needed when there is no change in node-to-node
    if (getNodeToNodeChange() > 0) {
      // Setting allow_insecure can be done in non-restart way
      createNonRestartUpgradeTaskFlow(
          (List<NodeDetails> nodeList, ServerType processType) -> {
            createGFlagUpdateTasks(2, nodeList, processType);
            Map<String, String> gflags = new HashMap<>();
            gflags.put("allow_insecure_connections", String.valueOf(taskParams().allowInsecure));
            createSetFlagInMemoryTasks(nodeList, processType, true, gflags, false)
                .setSubTaskGroupType(getTaskSubGroupType());
          },
          nodes);
    } else if (getNodeToNodeChange() < 0) {
      if (taskParams().upgradeOption == UpgradeOption.ROLLING_UPGRADE) {
        createRollingUpgradeTaskFlow(
            (List<NodeDetails> nodeList, ServerType processType) ->
                createGFlagUpdateTasks(2, nodeList, processType),
            nodes,
            true);
      } else if (taskParams().upgradeOption == UpgradeOption.NON_ROLLING_UPGRADE) {
        createNonRollingUpgradeTaskFlow(
            (List<NodeDetails> nodeList, ServerType processType) ->
                createGFlagUpdateTasks(2, nodeList, processType),
            nodes,
            true);
      }
    }
  }

  private void createGFlagUpdateTasks(int round, List<NodeDetails> nodes, ServerType processType) {
    // If the node list is empty, we don't need to do anything.
    if (nodes.isEmpty()) {
      return;
    }

    String subGroupDescription =
        String.format(
            "AnsibleConfigureServers (%s) for: %s", getTaskSubGroupType(), taskParams().nodePrefix);
    SubTaskGroup taskGroup = new SubTaskGroup(subGroupDescription, executor);
    for (NodeDetails node : nodes) {
      taskGroup.addTask(
          getAnsibleConfigureServerTask(
              node,
              processType,
              round == 1
                  ? UpgradeTaskSubType.Round1GFlagsUpdate
                  : UpgradeTaskSubType.Round2GFlagsUpdate));
    }
    taskGroup.setSubTaskGroupType(getTaskSubGroupType());
    subTaskGroupQueue.add(taskGroup);
  }

  private void createCopyCertTasks(List<NodeDetails> nodes) {
    // Copy cert tasks are not needed if TLS is disabled
    if (!taskParams().enableNodeToNodeEncrypt && !taskParams().enableClientToNodeEncrypt) {
      return;
    }

    String subGroupDescription =
        String.format(
            "AnsibleConfigureServers (%s) for: %s", getTaskSubGroupType(), taskParams().nodePrefix);
    SubTaskGroup copyCertGroup = new SubTaskGroup(subGroupDescription, executor);
    for (NodeDetails node : nodes) {
      copyCertGroup.addTask(
          getAnsibleConfigureServerTask(node, ServerType.TSERVER, UpgradeTaskSubType.CopyCerts));
    }
    copyCertGroup.setSubTaskGroupType(getTaskSubGroupType());
    subTaskGroupQueue.add(copyCertGroup);
  }

  private void createUniverseSetTlsParamsTask() {
    SubTaskGroup taskGroup = new SubTaskGroup("UniverseSetTlsParams", executor);

    UniverseSetTlsParams.Params params = new UniverseSetTlsParams.Params();
    params.universeUUID = taskParams().universeUUID;
    params.enableNodeToNodeEncrypt = taskParams().enableNodeToNodeEncrypt;
    params.enableClientToNodeEncrypt = taskParams().enableClientToNodeEncrypt;
    params.allowInsecure = taskParams().allowInsecure;
    params.rootCA = taskParams().rootCA;

    UniverseSetTlsParams task = createTask(UniverseSetTlsParams.class);
    task.initialize(params);
    taskGroup.addTask(task);

    taskGroup.setSubTaskGroupType(getTaskSubGroupType());
    subTaskGroupQueue.add(taskGroup);
  }

  private AnsibleConfigureServers getAnsibleConfigureServerTask(
      NodeDetails node, ServerType processType, UpgradeTaskSubType taskSubType) {
    AnsibleConfigureServers.Params params =
        getAnsibleConfigureServerParams(node, processType, UpgradeTaskType.ToggleTls, taskSubType);
    params.enableNodeToNodeEncrypt = taskParams().enableNodeToNodeEncrypt;
    params.enableClientToNodeEncrypt = taskParams().enableClientToNodeEncrypt;
    params.allowInsecure = taskParams().allowInsecure;
    params.rootCA = taskParams().rootCA;
    params.nodeToNodeChange = getNodeToNodeChange();
    AnsibleConfigureServers task = createTask(AnsibleConfigureServers.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    return task;
  }

  private int getNodeToNodeChange() {
    return getUserIntent().enableNodeToNodeEncrypt != taskParams().enableNodeToNodeEncrypt
        ? (taskParams().enableNodeToNodeEncrypt ? 1 : -1)
        : 0;
  }
}
