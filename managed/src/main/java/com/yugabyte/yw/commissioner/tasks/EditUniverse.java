/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.ITask.Abortable;
import com.yugabyte.yw.commissioner.ITask.Retryable;
import com.yugabyte.yw.commissioner.TaskExecutor;
import com.yugabyte.yw.commissioner.UpgradeTaskBase;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleConfigureServers;
import com.yugabyte.yw.commissioner.tasks.subtasks.ChangeMasterConfig;
import com.yugabyte.yw.common.DnsManager;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.PlacementInfoUtil.SelectMastersResult;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.config.UniverseConfKeys;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ClusterType;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.MasterState;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Tracks edit intents to the cluster and then performs the sequence of configuration changes on
// this universe to go from the current set of master/tserver nodes to the final configuration.
@Slf4j
@Abortable
@Retryable
public class EditUniverse extends UniverseDefinitionTaskBase {
  private final AtomicBoolean dedicatedNodesChanged = new AtomicBoolean();

  @Inject
  protected EditUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  public void validateParams(boolean isFirstTry) {
    super.validateParams(isFirstTry);
    if (isFirstTry) {
      // Verify the task params.
      verifyParams(UniverseOpType.EDIT);
    }
  }

  @Override
  protected void createPrecheckTasks(Universe universe) {
    addBasicPrecheckTasks();
    if (isFirstTry()) {
      configureTaskParams(universe);
    }
  }

  // Configure the task params in memory. These changes are committed in freeze callback.
  private void configureTaskParams(Universe universe) {
    // Set all the node names.
    setNodeNames(universe);
    // Set non on-prem node UUIDs.
    setCloudNodeUuids(universe);
    // Update on-prem node UUIDs in task params but do not commit yet.
    updateOnPremNodeUuidsOnTaskParams(false);
    // Select master nodes, if needed. Changes in masters are not automatically
    // applied.
    SelectMastersResult selection = selectMasters(universe.getMasterLeaderHostText());
    verifyMastersSelection(selection);

    // Applying changes to master flags for added masters only.
    // We are not clearing this flag according to selection.removedMasters in case
    // the master leader is to be changed and until the master leader is switched to
    // the new one.
    selection.addedMasters.forEach(
        n -> {
          n.isMaster = true;
          n.masterState = MasterState.ToStart;
        });
    selection.removedMasters.forEach(
        n -> {
          n.masterState = MasterState.ToStop;
        });
    createPreflightNodeCheckTasks(
        taskParams().clusters,
        PlacementInfoUtil.getNodesToProvision(taskParams().nodeDetailsSet),
        null,
        null);
  }

  private void freezeUniverseInTxn(Universe universe) {
    // Perform pre-task actions.
    preTaskActions(universe);
    // Confirm the nodes on hold.
    commitReservedNodes();
    // Set the prepared data to universe in-memory.
    updateUniverseNodesAndSettings(universe, taskParams(), false);
    // Task params contain the exact blueprint of what is desired.
    // There is a rare possibility that this succeeds and
    // saving the Universe fails. It is ok because the retry
    // will just fail.
    updateTaskDetailsInDB(taskParams());
  }

  @Override
  public void run() {
    log.info("Started {} task for uuid={}", getName(), taskParams().getUniverseUUID());
    checkUniverseVersion();
    String errorString = null;
    Universe universe = null;
    try {
      universe =
          lockAndFreezeUniverseForUpdate(
              taskParams().expectedUniverseVersion, this::freezeUniverseInTxn);
      if (taskParams().getPrimaryCluster() != null) {
        dedicatedNodesChanged.set(
            taskParams().getPrimaryCluster().userIntent.dedicatedNodes
                != universe.getUniverseDetails().getPrimaryCluster().userIntent.dedicatedNodes);
      }

      Set<NodeDetails> addedMasters =
          taskParams().nodeDetailsSet.stream()
              .filter(n -> n.masterState == MasterState.ToStart)
              .collect(Collectors.toSet());
      Set<NodeDetails> removedMasters =
          taskParams().nodeDetailsSet.stream()
              .filter(n -> n.masterState == MasterState.ToStop)
              .collect(Collectors.toSet());
      boolean updateMasters = !addedMasters.isEmpty() || !removedMasters.isEmpty();
      // Primary is always the first but there is no rule. So, sort it to do primary first.
      List<Cluster> clusters =
          taskParams().clusters.stream()
              .filter(
                  c -> c.clusterType == ClusterType.PRIMARY || c.clusterType == ClusterType.ASYNC)
              .sorted(
                  Comparator.<Cluster, Integer>comparing(
                      c -> c.clusterType == ClusterType.PRIMARY ? -1 : c.index))
              .collect(Collectors.toList());
      for (Cluster cluster : clusters) {
        // Updating cluster in memory
        universe
            .getUniverseDetails()
            .upsertCluster(cluster.userIntent, cluster.placementInfo, cluster.uuid);
        if (cluster.clusterType == ClusterType.PRIMARY && dedicatedNodesChanged.get()) {
          updateGFlagsForTservers(cluster, universe);
        }
        editCluster(
            universe,
            cluster,
            getNodesInCluster(cluster.uuid, addedMasters),
            getNodesInCluster(cluster.uuid, removedMasters),
            updateMasters);
        // Updating placement info and userIntent in DB
        createUpdateUniverseIntentTask(cluster);
      }

      // Wait for the master leader to hear from all tservers.
      // NOTE: Universe expansion will fail in the master leader failover scenario - if a node
      // is down externally for >15 minutes and the master leader then marks the node down for
      // real. Then that down TServer will timeout this task and universe expansion will fail.
      createWaitForTServerHeartBeatsTask().setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);

      // Update the DNS entry for this universe.
      createDnsManipulationTask(DnsManager.DnsCommandType.Edit, false, universe)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);

      // Marks the update of this universe as a success only if all the tasks before it succeeded.
      createMarkUniverseUpdateSuccessTasks()
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
      // Run all the tasks.
      getRunnableTask().runSubTasks();
    } catch (Throwable t) {
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      errorString = t.getMessage();
      throw t;
    } finally {
      releaseReservedNodes();
      // Mark the update of the universe as done. This will allow future edits/updates to the
      // universe to happen.
      universe = unlockUniverseForUpdate(errorString);

      if (universe != null
          && universe.getConfig().getOrDefault(Universe.USE_CUSTOM_IMAGE, "false").equals("true")) {
        universe.updateConfig(
            ImmutableMap.of(
                Universe.USE_CUSTOM_IMAGE,
                Boolean.toString(
                    universe.getUniverseDetails().nodeDetailsSet.stream()
                        .allMatch(n -> n.ybPrebuiltAmi))));
        universe.save();
      }
    }
    log.info("Finished {} task.", getName());
  }

  private void editCluster(
      Universe universe,
      Cluster cluster,
      Set<NodeDetails> newMasters,
      Set<NodeDetails> mastersToStop,
      boolean updateMasters) {
    UserIntent userIntent = cluster.userIntent;
    Set<NodeDetails> nodes = taskParams().getNodesInCluster(cluster.uuid);

    log.info(
        "Configure numNodes={}, Replication factor={}",
        userIntent.numNodes,
        userIntent.replicationFactor);

    Set<NodeDetails> nodesToBeRemoved = PlacementInfoUtil.getNodesToBeRemoved(nodes);

    Set<NodeDetails> nodesToProvision = PlacementInfoUtil.getNodesToProvision(nodes);

    Set<NodeDetails> removeMasters = PlacementInfoUtil.getMastersToBeRemoved(nodes);

    Set<NodeDetails> tserversToBeRemoved = PlacementInfoUtil.getTserversToBeRemoved(nodes);

    Set<NodeDetails> existingNodesToStartMaster =
        newMasters.stream().filter(n -> n.state != NodeState.ToBeAdded).collect(Collectors.toSet());

    boolean isWaitForLeadersOnPreferred =
        confGetter.getConfForScope(universe, UniverseConfKeys.ybEditWaitForLeadersOnPreferred);

    // Set the old nodes' state to to-be-removed.
    if (!nodesToBeRemoved.isEmpty()) {
      if (nodesToBeRemoved.size() == nodes.size()) {
        // Cluster must be deleted via cluster delete task.
        String errMsg = "All nodes cannot be removed for cluster " + cluster.uuid;
        log.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
      createSetNodeStateTasks(nodesToBeRemoved, NodeDetails.NodeState.ToBeRemoved)
          .setSubTaskGroupType(SubTaskGroupType.Provisioning);
    }

    Set<NodeDetails> liveNodes = PlacementInfoUtil.getLiveNodes(nodes);

    // Update any tags on nodes that are not going to be removed and not being added.
    Cluster existingCluster = getUniverse().getCluster(cluster.uuid);
    if (!cluster.areTagsSame(existingCluster)) {
      log.info(
          "Tags changed from '{}' to '{}'.",
          existingCluster.userIntent.instanceTags,
          cluster.userIntent.instanceTags);
      createUpdateInstanceTagsTasks(
          getNodesInCluster(cluster.uuid, liveNodes),
          cluster.userIntent.instanceTags,
          Util.getKeysNotPresent(
              existingCluster.userIntent.instanceTags, cluster.userIntent.instanceTags));
    }

    boolean ignoreUseCustomImageConfig =
        !taskParams().nodeDetailsSet.stream().allMatch(n -> n.ybPrebuiltAmi);

    if (!nodesToProvision.isEmpty()) {
      Map<UUID, List<NodeDetails>> nodesPerAZ =
          nodes.stream()
              .filter(
                  n ->
                      n.state != NodeDetails.NodeState.ToBeAdded
                          && n.state != NodeDetails.NodeState.ToBeRemoved)
              .collect(Collectors.groupingBy(n -> n.azUuid));

      nodesToProvision.forEach(
          node -> {
            Set<String> machineImages =
                nodesPerAZ.getOrDefault(node.azUuid, Collections.emptyList()).stream()
                    .map(n -> n.machineImage)
                    .collect(Collectors.toSet());
            Iterator<String> iterator = machineImages.iterator();

            if (iterator.hasNext()) {
              String imageToUse = iterator.next();

              if (iterator.hasNext()) {
                log.warn(
                    "Nodes in AZ {} are based on different machine images: {},"
                        + " falling back to default",
                    node.cloudInfo.az,
                    String.join(", ", machineImages));
              } else {
                node.machineImage = imageToUse;
              }
            }
          });

      // Provision the nodes.
      // State checking is enabled because the subtasks are not idempotent.
      createProvisionNodeTasks(
          universe,
          nodesToProvision,
          false /* ignore node status check */,
          setupServerParams -> {
            setupServerParams.ignoreUseCustomImageConfig = ignoreUseCustomImageConfig;
          },
          installSoftwareParams -> {
            installSoftwareParams.isMasterInShellMode = true;
            installSoftwareParams.ignoreUseCustomImageConfig = ignoreUseCustomImageConfig;
          },
          gFlagsParams -> {
            gFlagsParams.isMasterInShellMode = true;
            gFlagsParams.resetMasterState = true;
            gFlagsParams.ignoreUseCustomImageConfig = ignoreUseCustomImageConfig;
          });
      // Copy the source root certificate to the provisioned nodes.
      createTransferXClusterCertsCopyTasks(
          nodesToProvision, universe, SubTaskGroupType.Provisioning);
    }

    // Ensure all masters are covered in nodes to be removed.
    if (!removeMasters.isEmpty()) {
      if (nodesToBeRemoved.isEmpty()) {
        String errMsg = "If masters are being removed, corresponding nodes need removal too.";
        log.error(errMsg + " masters: " + nodeNames(removeMasters));
        throw new IllegalStateException(errMsg);
      }
      if (!nodesToBeRemoved.containsAll(removeMasters)) {
        String errMsg = "If masters are being removed, all those nodes need removal too.";
        log.error(
            errMsg
                + " masters: "
                + nodeNames(removeMasters)
                + " , but removing only "
                + nodeNames(nodesToBeRemoved));
        throw new IllegalStateException(errMsg);
      }
    }
    removeMasters.addAll(mastersToStop);

    // Creates the primary cluster by first starting the masters.
    if (!newMasters.isEmpty()) {
      if (cluster.clusterType == ClusterType.ASYNC) {
        String errMsg = "Read-only cluster " + cluster.uuid + " should not have masters.";
        log.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
      if (!existingNodesToStartMaster.isEmpty()) {
        // This changes the state of Master to Configured.
        // State check is done for these tasks because this is modified after the
        // master is started. It will reset the later change otherwise.
        createConfigureMasterTasks(
            universe,
            existingNodesToStartMaster,
            true /* shell mode */,
            false /* ignore node status check */,
            ignoreUseCustomImageConfig);
      }

      // Make sure clock skew is low enough.
      createWaitForClockSyncTasks(universe, newMasters)
          .setSubTaskGroupType(SubTaskGroupType.StartingMasterProcess);

      // Start masters. If it is already started, it has no effect.
      createStartMasterProcessTasks(newMasters);
    }

    Set<NodeDetails> newTservers = PlacementInfoUtil.getTserversToProvision(nodes);
    if (!newTservers.isEmpty()) {
      // Blacklist all the new tservers before starting so that they do not join.
      // Idempotent as same set of servers are blacklisted.
      createModifyBlackListTask(
              newTservers /* addNodes */, null /* removeNodes */, false /* isLeaderBlacklist */)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);

      // Make sure clock skew is low enough.
      createWaitForClockSyncTasks(universe, newTservers)
          .setSubTaskGroupType(SubTaskGroupType.StartingNodeProcesses);

      // Start tservers on all nodes.
      createStartTserverProcessTasks(newTservers, userIntent.enableYSQL);

      if (universe.isYbcEnabled()) {
        createStartYbcProcessTasks(
            newTservers, universe.getUniverseDetails().getPrimaryCluster().userIntent.useSystemd);
      }
    }
    if (!nodesToProvision.isEmpty()) {
      // Set the new nodes' state to live.
      createSetNodeStateTasks(nodesToProvision, NodeDetails.NodeState.Live)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
    }

    if (!newTservers.isEmpty() || !tserversToBeRemoved.isEmpty()) {
      // Swap the blacklisted tservers.
      // Idempotent as same set of servers are either blacklisted or removed.
      createModifyBlackListTask(
              tserversToBeRemoved /* addNodes */,
              newTservers /* removeNodes */,
              false /* isLeaderBlacklist */)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
    }
    // Update placement info on master leader.
    createPlacementInfoTask(null /* additional blacklist */, taskParams().clusters)
        .setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);

    if (!nodesToBeRemoved.isEmpty()) {
      // Wait for %age completion of the tablet move from master.
      createWaitForDataMoveTask().setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);
    } else {
      if (!tserversToBeRemoved.isEmpty()) {
        String errMsg = "Universe shrink should have been handled using node decommission.";
        log.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
      if (confGetter.getConfForScope(universe, UniverseConfKeys.waitForLbForAddedNodes)
          && !newTservers.isEmpty()) {
        // If only tservers are added, wait for load to balance across all tservers.
        createWaitForLoadBalanceTask().setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);
      }
    }

    // Add new nodes to load balancer.
    createManageLoadBalancerTasks(
        createLoadBalancerMap(taskParams(), ImmutableList.of(cluster), null, null));

    if (cluster.clusterType == ClusterType.PRIMARY && isWaitForLeadersOnPreferred) {
      createWaitForLeadersOnPreferredOnlyTask();
    }
    Set<NodeDetails> allTservers =
        Stream.concat(newTservers.stream(), liveNodes.stream()).collect(Collectors.toSet());
    Set<NodeDetails> allMasters =
        Stream.concat(newMasters.stream(), liveNodes.stream().filter(n -> n.isMaster))
            .collect(Collectors.toSet());
    // Filter out nodes which are not in the universe.
    Set<NodeDetails> removeUniverseMasters =
        filterUniverseNodes(universe, removeMasters, n -> true);
    if (!newMasters.isEmpty() || !removeUniverseMasters.isEmpty()) {
      // Now finalize the master quorum change tasks.
      createMoveMastersTasks(
          SubTaskGroupType.ConfigureUniverse,
          newMasters,
          removeUniverseMasters,
          (opType, node) -> {
            // Wait for a master leader to be elected.
            createWaitForMasterLeaderTask().setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
            if (opType == ChangeMasterConfig.OpType.RemoveMaster) {
              // Set isMaster to false before updating the addresses.
              createUpdateNodeProcessTasks(Collections.singleton(node), ServerType.MASTER, false)
                  .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
              // Remove this node from subsequent address update as it's no longer a master.
              allMasters.remove(node);
            }
            // Even masters to be stopped should be updated as they are still masters before all the
            // masters are moved.
            createMasterAddressUpdateTask(allMasters, allTservers);
          });
      if (!mastersToStop.isEmpty()) {
        createStopMasterTasks(mastersToStop)
            .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
      }
      // Do this once after all the master addresses are frozen as this is expensive.
      createXClusterConfigUpdateMasterAddressesTask();
    } else if (updateMasters) {
      // This is for async cluster after the primary masters are moved.
      // Wait for a master leader to be elected.
      createWaitForMasterLeaderTask().setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
      // Change the master addresses in the conf file for all tservers.
      createMasterAddressUpdateTask(allMasters, allTservers);
      // Update the master addresses on the target universes whose source universe belongs to
      // this task.
      createXClusterConfigUpdateMasterAddressesTask();
    }

    // Stop scrapping metrics from TServers that is set to be removed
    if (!newTservers.isEmpty()
        || !newMasters.isEmpty()
        || !tserversToBeRemoved.isEmpty()
        || !removeMasters.isEmpty()
        || !nodesToBeRemoved.isEmpty()) {
      // Update the swamper target file.
      createSwamperTargetUpdateTask(false /* removeFile */);
    }

    // Finally send destroy to the old set of nodes and remove them from this universe.
    if (!nodesToBeRemoved.isEmpty()) {
      // Remove nodes from load balancer.
      createManageLoadBalancerTasks(
          createLoadBalancerMap(taskParams(), ImmutableList.of(cluster), nodesToBeRemoved, null));

      // Set the node states to Removing.
      createSetNodeStateTasks(nodesToBeRemoved, NodeDetails.NodeState.Terminating)
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      createDestroyServerTasks(
              universe,
              nodesToBeRemoved,
              false /* isForceDelete */,
              true /* deleteNode */,
              true /* deleteRootVolumes */,
              false /* skipDestroyPrecheck */)
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
    }

    if (!tserversToBeRemoved.isEmpty()) {
      // Clear blacklisted tservers.
      createModifyBlackListTask(
              null /* addNodes */,
              tserversToBeRemoved /* removeNodes */,
              false /* isLeaderBlacklist */)
          .setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
    }
  }

  private void updateGFlagsForTservers(Cluster cluster, Universe universe) {
    List<NodeDetails> tservers =
        getNodesInCluster(cluster.uuid, taskParams().nodeDetailsSet).stream()
            .filter(t -> t.isTserver)
            .filter(t -> t.state == NodeState.Live)
            .collect(Collectors.toList());
    UpgradeTaskBase.sortTServersInRestartOrder(universe, tservers);
    removeFromLeaderBlackListIfAvailable(tservers, SubTaskGroupType.UpdatingGFlags);
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("AnsibleConfigureServers");
    for (NodeDetails nodeDetails : tservers) {
      stopProcessesOnNode(
          nodeDetails,
          EnumSet.of(ServerType.TSERVER),
          false,
          false,
          SubTaskGroupType.UpdatingGFlags);

      AnsibleConfigureServers.Params params =
          getAnsibleConfigureServerParams(
              cluster.userIntent,
              nodeDetails,
              ServerType.TSERVER,
              UpgradeTaskParams.UpgradeTaskType.GFlags,
              UpgradeTaskParams.UpgradeTaskSubType.None);
      params.gflags = cluster.userIntent.tserverGFlags;
      AnsibleConfigureServers task = createTask(AnsibleConfigureServers.class);
      task.initialize(params);
      task.setUserTaskUUID(getUserTaskUUID());
      subTaskGroup.addSubTask(task);
      getRunnableTask().addSubTaskGroup(subTaskGroup);

      startProcessesOnNode(
          nodeDetails,
          EnumSet.of(ServerType.TSERVER),
          SubTaskGroupType.UpdatingGFlags,
          false,
          true,
          (x) -> UniverseTaskParams.DEFAULT_SLEEP_AFTER_RESTART_MS);
    }
  }

  /**
   * Fills in the series of steps needed to move the masters using the node names. The actual node
   * details (such as ip addresses) are found at runtime by querying the database.
   */
  private void createMoveMastersTasks(
      SubTaskGroupType subTaskGroupType,
      Set<NodeDetails> newMasters,
      Set<NodeDetails> removeMasters,
      BiConsumer<ChangeMasterConfig.OpType, NodeDetails> postChangeConfigCallback) {

    // Get the list of node names to add as masters.
    List<NodeDetails> mastersToAdd = new ArrayList<>(newMasters);
    // Get the list of node names to remove as masters.
    List<NodeDetails> mastersToRemove = new ArrayList<>(removeMasters);

    // Find the minimum number of master changes where we can perform an add followed by a remove.
    int numIters = Math.min(mastersToAdd.size(), mastersToRemove.size());

    // Perform a master add followed by a remove if possible. Need not remove the (current) master
    // leader last - even if we get current leader, it might change by the time we run the actual
    // task. So we might do multiple leader stepdown's, which happens automatically in the
    // client code during the task's run.
    for (int idx = 0; idx < numIters; idx++) {
      createChangeConfigTasks(mastersToAdd.get(idx), true, subTaskGroupType);
      postChangeConfigCallback.accept(ChangeMasterConfig.OpType.AddMaster, mastersToAdd.get(idx));
      createChangeConfigTasks(mastersToRemove.get(idx), false, subTaskGroupType);
      postChangeConfigCallback.accept(
          ChangeMasterConfig.OpType.RemoveMaster, mastersToRemove.get(idx));
    }

    // Perform any additions still left.
    for (int idx = numIters; idx < mastersToAdd.size(); idx++) {
      createChangeConfigTasks(mastersToAdd.get(idx), true, subTaskGroupType);
      postChangeConfigCallback.accept(ChangeMasterConfig.OpType.AddMaster, mastersToAdd.get(idx));
    }

    // Perform any removals still left.
    for (int idx = numIters; idx < mastersToRemove.size(); idx++) {
      createChangeConfigTasks(mastersToRemove.get(idx), false, subTaskGroupType);
      postChangeConfigCallback.accept(
          ChangeMasterConfig.OpType.RemoveMaster, mastersToRemove.get(idx));
    }
  }
}
