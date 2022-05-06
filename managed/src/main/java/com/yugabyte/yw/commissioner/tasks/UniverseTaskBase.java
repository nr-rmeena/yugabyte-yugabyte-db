// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.yugabyte.yw.common.Util.SYSTEM_PLATFORM_DB;
import static com.yugabyte.yw.forms.UniverseTaskParams.isFirstTryForTask;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.util.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Streams;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.HealthChecker;
import com.yugabyte.yw.commissioner.TaskExecutor;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleClusterServerCtl;
import com.yugabyte.yw.commissioner.tasks.subtasks.AnsibleDestroyServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.BackupTable;
import com.yugabyte.yw.commissioner.tasks.subtasks.BackupTableYb;
import com.yugabyte.yw.commissioner.tasks.subtasks.BackupUniverseKeys;
import com.yugabyte.yw.commissioner.tasks.subtasks.BulkImport;
import com.yugabyte.yw.commissioner.tasks.subtasks.ChangeAdminPassword;
import com.yugabyte.yw.commissioner.tasks.subtasks.ChangeMasterConfig;
import com.yugabyte.yw.commissioner.tasks.subtasks.CreateAlertDefinitions;
import com.yugabyte.yw.commissioner.tasks.subtasks.CreateTable;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteBackup;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteBackupYb;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteNode;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteTableFromUniverse;
import com.yugabyte.yw.commissioner.tasks.subtasks.DestroyEncryptionAtRest;
import com.yugabyte.yw.commissioner.tasks.subtasks.DisableEncryptionAtRest;
import com.yugabyte.yw.commissioner.tasks.subtasks.EnableEncryptionAtRest;
import com.yugabyte.yw.commissioner.tasks.subtasks.LoadBalancerStateChange;
import com.yugabyte.yw.commissioner.tasks.subtasks.ManageAlertDefinitions;
import com.yugabyte.yw.commissioner.tasks.subtasks.ManipulateDnsRecordTask;
import com.yugabyte.yw.commissioner.tasks.subtasks.ModifyBlackList;
import com.yugabyte.yw.commissioner.tasks.subtasks.PauseServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.PersistResizeNode;
import com.yugabyte.yw.commissioner.tasks.subtasks.PersistSystemdUpgrade;
import com.yugabyte.yw.commissioner.tasks.subtasks.ResetUniverseVersion;
import com.yugabyte.yw.commissioner.tasks.subtasks.RestoreBackupYb;
import com.yugabyte.yw.commissioner.tasks.subtasks.RestoreUniverseKeys;
import com.yugabyte.yw.commissioner.tasks.subtasks.RestoreUniverseKeysYb;
import com.yugabyte.yw.commissioner.tasks.subtasks.ResumeServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.RunYsqlUpgrade;
import com.yugabyte.yw.commissioner.tasks.subtasks.SetActiveUniverseKeys;
import com.yugabyte.yw.commissioner.tasks.subtasks.SetFlagInMemory;
import com.yugabyte.yw.commissioner.tasks.subtasks.SetNodeState;
import com.yugabyte.yw.commissioner.tasks.subtasks.SetNodeStatus;
import com.yugabyte.yw.commissioner.tasks.subtasks.SwamperTargetsFileUpdate;
import com.yugabyte.yw.commissioner.tasks.subtasks.UnivSetCertificate;
import com.yugabyte.yw.commissioner.tasks.subtasks.UniverseUpdateSucceeded;
import com.yugabyte.yw.commissioner.tasks.subtasks.UpdateAndPersistGFlags;
import com.yugabyte.yw.commissioner.tasks.subtasks.UpdateMountedDisks;
import com.yugabyte.yw.commissioner.tasks.subtasks.UpdatePlacementInfo;
import com.yugabyte.yw.commissioner.tasks.subtasks.UpdateSoftwareVersion;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForDataMove;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForEncryptionKeyInMemory;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForFollowerLag;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForLeaderBlacklistCompletion;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForLeadersOnPreferredOnly;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForLoadBalance;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForMasterLeader;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForServer;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForServerReady;
import com.yugabyte.yw.commissioner.tasks.subtasks.check.CheckMemory;
import com.yugabyte.yw.commissioner.tasks.subtasks.nodes.UpdateNodeProcess;
import com.yugabyte.yw.commissioner.tasks.subtasks.xcluster.XClusterConfigUpdateMasterAddresses;
import com.yugabyte.yw.common.DnsManager;
import com.yugabyte.yw.common.NodeManager;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.forms.BulkImportParams;
import com.yugabyte.yw.forms.EncryptionAtRestConfig.OpType;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.forms.RestoreBackupParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.HighAvailabilityConfig;
import com.yugabyte.yw.models.NodeInstance;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.helpers.ColumnDetails;
import com.yugabyte.yw.models.helpers.ColumnDetails.YQLDataType;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeStatus;
import com.yugabyte.yw.models.helpers.TableDetails;
import com.yugabyte.yw.models.helpers.TaskType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.slf4j.MDC;
import org.yb.ColumnSchema.SortOrder;
import org.yb.CommonTypes.TableType;
import org.yb.client.ModifyClusterConfigIncrementVersion;
import org.yb.client.YBClient;
import play.api.Play;
import play.libs.Json;

@Slf4j
public abstract class UniverseTaskBase extends AbstractTaskBase {

  enum VersionCheckMode {
    NEVER,
    ALWAYS,
    HA_ONLY
  }

  // Flag to indicate if we have locked the universe.
  private boolean universeLocked = false;

  @Inject
  protected UniverseTaskBase(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  private Universe universe = null;

  // The task params.
  @Override
  protected UniverseTaskParams taskParams() {
    return (UniverseTaskParams) taskParams;
  }

  protected Universe getUniverse() {
    return getUniverse(false);
  }

  protected Universe getUniverse(boolean fetchFromDB) {
    if (fetchFromDB) {
      return Universe.getOrBadRequest(taskParams().universeUUID);
    } else {
      if (universe == null) {
        universe = Universe.getOrBadRequest(taskParams().universeUUID);
      }
      return universe;
    }
  }

  protected boolean isLeaderBlacklistValidRF(String nodeName) {
    Cluster curCluster = Universe.getCluster(getUniverse(), nodeName);
    if (curCluster == null) {
      return false;
    }
    return curCluster.userIntent.replicationFactor > 1;
  }

  protected UserIntent getUserIntent() {
    return getUserIntent(false);
  }

  protected UserIntent getUserIntent(boolean fetchFromDB) {
    return getUniverse(fetchFromDB).getUniverseDetails().getPrimaryCluster().userIntent;
  }

  private UniverseUpdater getLockingUniverseUpdater(
      int expectedUniverseVersion, boolean checkSuccess) {
    return getLockingUniverseUpdater(expectedUniverseVersion, checkSuccess, false, false);
  }

  private UniverseUpdater getLockingUniverseUpdater(
      int expectedUniverseVersion,
      boolean checkSuccess,
      boolean isForceUpdate,
      boolean isResumeOrDelete) {
    return getLockingUniverseUpdater(
        expectedUniverseVersion, checkSuccess, isForceUpdate, isResumeOrDelete, null);
  }

  private UniverseUpdater getLockingUniverseUpdater(
      int expectedUniverseVersion,
      boolean checkSuccess,
      boolean isForceUpdate,
      boolean isResumeOrDelete,
      Consumer<Universe> callback) {
    TaskType owner = TaskExecutor.getTaskType(getClass());
    if (owner == null) {
      log.trace("TaskType not found for class " + this.getClass().getCanonicalName());
    }
    return new UniverseUpdater() {
      @Override
      public void run(Universe universe) {
        if (isFirstTryForTask(taskParams())) {
          // Universe already has a reference to the last task UUID in case of retry.
          // Check version only when it is a first try.
          verifyUniverseVersion(expectedUniverseVersion, universe);
        }
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        if (universeDetails.universePaused && !isResumeOrDelete) {
          String msg = "Universe " + taskParams().universeUUID + " is currently paused";
          log.error(msg);
          throw new RuntimeException(msg);
        }
        // If this universe is already being edited, fail the request.
        if (!isForceUpdate && universeDetails.updateInProgress) {
          String msg = "Universe " + taskParams().universeUUID + " is already being updated.";
          log.error(msg);
          throw new RuntimeException(msg);
        }
        // If the task is retried, check if the task UUID is same as the one in the universe.
        // Check this condition only on retry to retain same behavior as before.
        if (!isForceUpdate
            && !universeDetails.updateSucceeded
            && taskParams().previousTaskUUID != null
            && !Objects.equal(taskParams().previousTaskUUID, universeDetails.updatingTaskUUID)) {
          String msg = "Only the last task " + taskParams().previousTaskUUID + " can be retried";
          log.error(msg);
          throw new RuntimeException(msg);
        }
        markUniverseUpdateInProgress(owner, universe, checkSuccess);
        if (callback != null) {
          callback.accept(universe);
        }
      }
    };
  }

  private void markUniverseUpdateInProgress(
      TaskType owner, Universe universe, boolean checkSuccess) {
    UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
    // Persist the updated information about the universe. Mark it as being edited.
    universeDetails.updateInProgress = true;
    universeDetails.updatingTask = owner;
    universeDetails.updatingTaskUUID = userTaskUUID;
    if (checkSuccess) {
      universeDetails.updateSucceeded = false;
    }
    universe.setUniverseDetails(universeDetails);
  }

  /**
   * verifyUniverseVersion
   *
   * @param expectedUniverseVersion
   * @param universe
   *     <p>This is attempting to flag situations where the UI is operating on a stale copy of the
   *     universe for example, when multiple browsers or users are operating on the same universe.
   *     <p>This assumes that the UI supplies the expectedUniverseVersion in the API call but this
   *     is not always true. If the UI does not supply it, expectedUniverseVersion is set from
   *     universe.version itself so this check is not useful in that case.
   */
  public void verifyUniverseVersion(int expectedUniverseVersion, Universe universe) {
    if (expectedUniverseVersion != -1 && expectedUniverseVersion != universe.version) {
      String msg =
          "Universe "
              + taskParams().universeUUID
              + " version "
              + universe.version
              + ", is different from the expected version of "
              + expectedUniverseVersion
              + ". User "
              + "would have to sumbit the operation from a refreshed top-level universe page.";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  private Universe lockUniverseForUpdate(int expectedUniverseVersion, UniverseUpdater updater) {
    // Perform the update. If unsuccessful, this will throw a runtime exception which we do not
    // catch as we want to fail.
    Universe universe = saveUniverseDetails(updater);
    universeLocked = true;
    log.trace(
        "Locked universe {} at version {}.", taskParams().universeUUID, expectedUniverseVersion);
    // Return the universe object that we have already updated.
    return universe;
  }

  public SubTaskGroup createManageEncryptionAtRestTask() {
    SubTaskGroup subTaskGroup = null;
    AbstractTaskBase task = null;
    switch (taskParams().encryptionAtRestConfig.opType) {
      case ENABLE:
        subTaskGroup = getTaskExecutor().createSubTaskGroup("EnableEncryptionAtRest", executor);
        task = createTask(EnableEncryptionAtRest.class);
        EnableEncryptionAtRest.Params enableParams = new EnableEncryptionAtRest.Params();
        enableParams.universeUUID = taskParams().universeUUID;
        enableParams.encryptionAtRestConfig = taskParams().encryptionAtRestConfig;
        task.initialize(enableParams);
        subTaskGroup.addSubTask(task);
        getRunnableTask().addSubTaskGroup(subTaskGroup);
        break;
      case DISABLE:
        subTaskGroup = getTaskExecutor().createSubTaskGroup("DisableEncryptionAtRest", executor);
        task = createTask(DisableEncryptionAtRest.class);
        DisableEncryptionAtRest.Params disableParams = new DisableEncryptionAtRest.Params();
        disableParams.universeUUID = taskParams().universeUUID;
        task.initialize(disableParams);
        subTaskGroup.addSubTask(task);
        getRunnableTask().addSubTaskGroup(subTaskGroup);
        break;
      default:
      case UNDEFINED:
        break;
    }

    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            log.info(
                String.format(
                    "Setting encryption at rest status to %s for universe %s",
                    taskParams().encryptionAtRestConfig.opType.name(),
                    universe.universeUUID.toString()));
            // Persist the updated information about the universe.
            // It should have been marked as being edited in lockUniverseForUpdate().
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            if (!universeDetails.updateInProgress) {
              String msg =
                  "Universe "
                      + taskParams().universeUUID
                      + " has not been marked as being updated.";
              log.error(msg);
              throw new RuntimeException(msg);
            }

            universeDetails.encryptionAtRestConfig = taskParams().encryptionAtRestConfig;

            universeDetails.encryptionAtRestConfig.encryptionAtRestEnabled =
                taskParams().encryptionAtRestConfig.opType.equals(OpType.ENABLE);
            universe.setUniverseDetails(universeDetails);
          }
        };
    // Perform the update. If unsuccessful, this will throw a runtime exception which we do not
    // catch as we want to fail.
    saveUniverseDetails(updater);
    log.trace("Wrote user intent for universe {}.", taskParams().universeUUID);

    return subTaskGroup;
  }

  public SubTaskGroup createSetActiveUniverseKeysTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("SetActiveUniverseKeys", executor);
    SetActiveUniverseKeys task = createTask(SetActiveUniverseKeys.class);
    SetActiveUniverseKeys.Params params = new SetActiveUniverseKeys.Params();
    params.universeUUID = taskParams().universeUUID;
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createDestroyEncryptionAtRestTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("DestroyEncryptionAtRest", executor);
    DestroyEncryptionAtRest task = createTask(DestroyEncryptionAtRest.class);
    DestroyEncryptionAtRest.Params params = new DestroyEncryptionAtRest.Params();
    params.universeUUID = taskParams().universeUUID;
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  @Override
  public void initialize(ITaskParams params) {
    super.initialize(params);
    if (taskParams().universeUUID != null) {
      MDC.put("universe-id", taskParams().universeUUID.toString());
    }
    // Create the threadpool for the subtasks to use.
    createThreadpool();
  }

  @Override
  public String getName() {
    return super.getName() + "(" + taskParams().universeUUID + ")";
  }

  /**
   * Locks the universe for updates by setting the 'updateInProgress' flag. If the universe is
   * already being modified, then throws an exception.
   *
   * @param expectedUniverseVersion Lock only if the current version of the universe is at this
   *     version. -1 implies always lock the universe.
   * @param callback Callback is invoked for any pre-processing to be done on the Universe before it
   *     is saved in transaction with 'updateInProgress' flag.
   * @return
   */
  public Universe lockUniverseForUpdate(int expectedUniverseVersion, Consumer<Universe> callback) {
    UniverseUpdater updater =
        getLockingUniverseUpdater(expectedUniverseVersion, true, false, false, callback);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  /**
   * Locks the universe for updates by setting the 'updateInProgress' flag. If the universe is
   * already being modified, then throws an exception.
   *
   * @param expectedUniverseVersion Lock only if the current version of the universe is at this
   *     version. -1 implies always lock the universe.
   */
  public Universe lockUniverseForUpdate(int expectedUniverseVersion) {
    UniverseUpdater updater =
        getLockingUniverseUpdater(expectedUniverseVersion, true, false, false);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  public Universe lockUniverseForUpdate(int expectedUniverseVersion, boolean isResumeOrDelete) {
    UniverseUpdater updater =
        getLockingUniverseUpdater(expectedUniverseVersion, true, false, isResumeOrDelete);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  public Universe forceLockUniverseForUpdate(int expectedUniverseVersion) {
    log.info(
        "Force lock universe {} at version {}.",
        taskParams().universeUUID,
        expectedUniverseVersion);
    UniverseUpdater updater = getLockingUniverseUpdater(expectedUniverseVersion, true, true, false);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  public Universe forceLockUniverseForUpdate(
      int expectedUniverseVersion, boolean isResumeOrDelete) {
    log.info(
        "Force lock universe {} at version {}.",
        taskParams().universeUUID,
        expectedUniverseVersion);
    UniverseUpdater updater =
        getLockingUniverseUpdater(expectedUniverseVersion, true, true, isResumeOrDelete);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  /**
   * Locks the universe by setting the 'updateInProgress' flag. If the universe is already being
   * modified, then throws an exception. Any tasks involving tables should use this method, not any
   * other.
   *
   * @param expectedUniverseVersion Lock only if the current version of the unvierse is at this
   *     version. -1 implies always lock the universe.
   */
  public Universe lockUniverse(int expectedUniverseVersion) {
    UniverseUpdater updater = getLockingUniverseUpdater(expectedUniverseVersion, false);
    return lockUniverseForUpdate(expectedUniverseVersion, updater);
  }

  public Universe unlockUniverseForUpdate() {
    return unlockUniverseForUpdate(null);
  }

  public Universe unlockUniverseForUpdate(String error) {
    UUID universeUUID = taskParams().universeUUID;
    if (!universeLocked) {
      log.warn("Unlock universe({}) called when it was not locked.", universeUUID);
      return null;
    }
    final String err = error;
    // Create the update lambda.
    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            // If this universe is not being edited, fail the request.
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            if (!universeDetails.updateInProgress) {
              String msg = "Universe " + taskParams().universeUUID + " is not being edited.";
              log.error(msg);
              throw new RuntimeException(msg);
            }
            // Persist the updated information about the universe. Mark it as being edited.
            universeDetails.updateInProgress = false;
            universeDetails.updatingTask = null;
            universeDetails.errorString = err;
            if (universeDetails.updateSucceeded) {
              // Clear the task UUID only if the update succeeded.
              universeDetails.updatingTaskUUID = null;
              // Do not save the transient state in the universe.
              universeDetails.nodeDetailsSet.forEach(
                  n -> {
                    n.masterState = null;
                  });
            }
            universe.setUniverseDetails(universeDetails);
          }
        };
    // Update the progress flag to false irrespective of the version increment failure.
    // Universe version in master does not need to be updated as this does not change
    // the Universe state. It simply sets updateInProgress flag to false.
    universe = Universe.saveDetails(universeUUID, updater, false);
    universeLocked = false;
    log.trace("Unlocked universe {} for updates.", universeUUID);
    return universe;
  }

  /** Create a task to mark the change on a universe as success. */
  public SubTaskGroup createMarkUniverseUpdateSuccessTasks() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("FinalizeUniverseUpdate", executor);
    UniverseUpdateSucceeded.Params params = new UniverseUpdateSucceeded.Params();
    params.universeUUID = taskParams().universeUUID;
    UniverseUpdateSucceeded task = createTask(UniverseUpdateSucceeded.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createChangeAdminPasswordTask(
      Cluster primaryCluster,
      String ysqlPassword,
      String ysqlCurrentPassword,
      String ysqlUserName,
      String ysqlDbName,
      String ycqlPassword,
      String ycqlCurrentPassword,
      String ycqlUserName) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("ChangeAdminPassword", executor);
    ChangeAdminPassword.Params params = new ChangeAdminPassword.Params();
    params.universeUUID = taskParams().universeUUID;
    params.primaryCluster = primaryCluster;
    params.ycqlNewPassword = ycqlPassword;
    params.ysqlNewPassword = ysqlPassword;
    params.ycqlCurrentPassword = ycqlCurrentPassword;
    params.ysqlCurrentPassword = ysqlCurrentPassword;
    params.ycqlUserName = ycqlUserName;
    params.ysqlUserName = ysqlUserName;
    params.ysqlDbName = ysqlDbName;
    ChangeAdminPassword task = createTask(ChangeAdminPassword.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to mark the final software version on a universe. */
  public SubTaskGroup createUpdateSoftwareVersionTask(
      String softwareVersion, boolean isSoftwareUpdateViaVm) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("FinalizeUniverseUpdate", executor);
    UpdateSoftwareVersion.Params params = new UpdateSoftwareVersion.Params();
    params.universeUUID = taskParams().universeUUID;
    params.softwareVersion = softwareVersion;
    params.prevSoftwareVersion = taskParams().ybPrevSoftwareVersion;
    params.isSoftwareUpdateViaVm = isSoftwareUpdateViaVm;
    UpdateSoftwareVersion task = createTask(UpdateSoftwareVersion.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createUpdateSoftwareVersionTask(String softwareVersion) {
    return createUpdateSoftwareVersionTask(softwareVersion, false /*isSoftwareUpdateViaVm*/);
  }

  /** Create a task to run YSQL upgrade on the universe. */
  public SubTaskGroup createRunYsqlUpgradeTask(String ybSoftwareVersion) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("RunYsqlUpgrade", executor);

    RunYsqlUpgrade task = createTask(RunYsqlUpgrade.class);

    RunYsqlUpgrade.Params params = new RunYsqlUpgrade.Params();
    params.universeUUID = taskParams().universeUUID;
    params.ybSoftwareVersion = ybSoftwareVersion;

    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to check memory limit on the universe nodes */
  public SubTaskGroup createAvailabeMemoryCheck(
      List<NodeDetails> nodes, String memoryType, Long memoryLimitKB) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("CheckMemory", executor);
    CheckMemory task = createTask(CheckMemory.class);
    CheckMemory.Params params = new CheckMemory.Params();
    params.universeUUID = taskParams().universeUUID;
    params.memoryType = memoryType;
    params.memoryLimitKB = memoryLimitKB;
    params.nodeIpList =
        nodes.stream().map(node -> node.cloudInfo.private_ip).collect(Collectors.toList());
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createPersistResizeNodeTask(String instanceType) {
    return createPersistResizeNodeTask(instanceType, null);
  }

  /** Create a task to persist changes by ResizeNode task */
  public SubTaskGroup createPersistResizeNodeTask(String instanceType, Integer volumeSize) {
    return createPersistResizeNodeTask(instanceType, volumeSize, null);
  }

  /** Create a task to persist changes by ResizeNode task for specific clusters */
  public SubTaskGroup createPersistResizeNodeTask(
      String instanceType, Integer volumeSize, List<UUID> clusterIds) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("PersistResizeNode", executor);
    PersistResizeNode.Params params = new PersistResizeNode.Params();
    params.universeUUID = taskParams().universeUUID;
    params.instanceType = instanceType;
    params.volumeSize = volumeSize;
    params.clusters = clusterIds;
    PersistResizeNode task = createTask(PersistResizeNode.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to persist changes by Systemd Upgrade task */
  public SubTaskGroup createPersistSystemdUpgradeTask(Boolean useSystemd) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("PersistSystemdUpgrade", executor);
    PersistSystemdUpgrade.Params params = new PersistSystemdUpgrade.Params();
    params.universeUUID = taskParams().universeUUID;
    params.useSystemd = useSystemd;
    PersistSystemdUpgrade task = createTask(PersistSystemdUpgrade.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to mark the updated cert on a universe. */
  public SubTaskGroup createUnivSetCertTask(UUID certUUID) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("FinalizeUniverseUpdate", executor);
    UnivSetCertificate.Params params = new UnivSetCertificate.Params();
    params.universeUUID = taskParams().universeUUID;
    params.certUUID = certUUID;
    UnivSetCertificate task = createTask(UnivSetCertificate.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to create default alert definitions on a universe. */
  public SubTaskGroup createUnivCreateAlertDefinitionsTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("CreateAlertDefinitions", executor);
    CreateAlertDefinitions task = createTask(CreateAlertDefinitions.class);
    task.initialize(taskParams());
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to activate or diactivate universe alert definitions. */
  public SubTaskGroup createUnivManageAlertDefinitionsTask(boolean active) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("ManageAlertDefinitions", executor);
    ManageAlertDefinitions task = createTask(ManageAlertDefinitions.class);
    ManageAlertDefinitions.Params params = new ManageAlertDefinitions.Params();
    params.universeUUID = taskParams().universeUUID;
    params.active = active;
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to destroy nodes and adds it to the task queue.
   *
   * @param nodes : a collection of nodes that need to be removed
   * @param isForceDelete if this is true, ignore ansible errors
   * @param deleteNode if true, the node info is deleted from the universe db.
   * @param deleteRootVolumes if true, the volumes are deleted.
   */
  public SubTaskGroup createDestroyServerTasks(
      Collection<NodeDetails> nodes,
      boolean isForceDelete,
      boolean deleteNode,
      boolean deleteRootVolumes) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleDestroyServers", executor);
    for (NodeDetails node : nodes) {
      // Check if the private ip for the node is set. If not, that means we don't have
      // a clean state to delete the node. Log it, free up the onprem node
      // so that the client can use the node instance to create another universe.
      if (node.cloudInfo.private_ip == null) {
        log.warn(
            String.format(
                "Node %s doesn't have a private IP. Skipping node delete.", node.nodeName));
        if (node.cloudInfo.cloud.equals(
            com.yugabyte.yw.commissioner.Common.CloudType.onprem.name())) {
          try {
            NodeInstance providerNode = NodeInstance.getByName(node.nodeName);
            providerNode.clearNodeDetails();
          } catch (Exception ex) {
            log.warn("On-prem node {} doesn't have a linked instance ", node.nodeName);
          }
          continue;
        }
        if (node.nodeUuid == null) {
          // No other way to identify the node.
          continue;
        }
      }
      AnsibleDestroyServer.Params params = new AnsibleDestroyServer.Params();
      // Set the device information (numVolumes, volumeSize, etc.)
      params.deviceInfo = taskParams().deviceInfo;
      // Set the region name to the proper provider code so we can use it in the cloud API calls.
      params.azUuid = node.azUuid;
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the node UUID.
      params.nodeUuid = node.nodeUuid;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // Flag to be set where errors during Ansible Destroy Server will be ignored.
      params.isForceDelete = isForceDelete;
      // Flag to track if node info should be deleted from universe db.
      params.deleteNode = deleteNode;
      // Flag to track if volumes should be deleted from universe.
      params.deleteRootVolumes = deleteRootVolumes;
      // Add the instance type
      params.instanceType = node.cloudInfo.instance_type;
      // Assign the node IP to ensure deletion of the correct node.
      params.nodeIP = node.cloudInfo.private_ip;
      // Create the Ansible task to destroy the server.
      AnsibleDestroyServer task = createTask(AnsibleDestroyServer.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to pause the nodes and adds to the task queue.
   *
   * @param nodes : a collection of nodes that need to be paused.
   */
  public SubTaskGroup createPauseServerTasks(Collection<NodeDetails> nodes) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("PauseServer", executor);
    for (NodeDetails node : nodes) {
      // Check if the private ip for the node is set. If not, that means we don't have
      // a clean state to pause the node. Log it and skip the node.
      if (node.cloudInfo.private_ip == null) {
        log.warn(
            String.format("Node %s doesn't have a private IP. Skipping pause.", node.nodeName));
        continue;
      }
      PauseServer.Params params = new PauseServer.Params();
      // Set the device information (numVolumes, volumeSize, etc.)
      params.deviceInfo = taskParams().deviceInfo;
      // Set the region name to the proper provider code so we can use it in the cloud API calls.
      params.azUuid = node.azUuid;
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // Add the instance type
      params.instanceType = node.cloudInfo.instance_type;
      // Assign the node IP to pause the node.
      params.nodeIP = node.cloudInfo.private_ip;
      // Create the task to pause the server.
      PauseServer task = createTask(PauseServer.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to resume nodes and adds it to the task queue.
   *
   * @param nodes : a collection of nodes that need to be resumed.
   */
  public SubTaskGroup createResumeServerTasks(Collection<NodeDetails> nodes) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("ResumeServer", executor);
    for (NodeDetails node : nodes) {
      // Check if the private ip for the node is set. If not, that means we don't have
      // a clean state to resume the node. Log it and skip the node.
      if (node.cloudInfo.private_ip == null) {
        log.warn(
            String.format(
                "Node %s doesn't have a private IP. Skipping node resume.", node.nodeName));
        continue;
      }
      ResumeServer.Params params = new ResumeServer.Params();
      // Set the device information (numVolumes, volumeSize, etc.)
      params.deviceInfo = taskParams().deviceInfo;
      // Set the region name to the proper provider code so we can use it in the cloud API calls.
      params.azUuid = node.azUuid;
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // Add the instance type
      params.instanceType = node.cloudInfo.instance_type;
      // Assign the node IP to resume the nodes.
      params.nodeIP = node.cloudInfo.private_ip;
      // Create the task to resume the server.
      ResumeServer task = createTask(ResumeServer.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create tasks to update the state of the nodes.
   *
   * @param nodes set of nodes to be updated.
   * @param nodeState State into which these nodes will be transitioned.
   */
  public SubTaskGroup createSetNodeStateTasks(
      Collection<NodeDetails> nodes, NodeDetails.NodeState nodeState) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("SetNodeState", executor);
    for (NodeDetails node : nodes) {
      SetNodeState.Params params = new SetNodeState.Params();
      params.universeUUID = taskParams().universeUUID;
      params.azUuid = node.azUuid;
      params.nodeName = node.nodeName;
      params.state = nodeState;
      SetNodeState task = createTask(SetNodeState.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createWaitForKeyInMemoryTask(NodeDetails node) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForEncryptionKeyInMemory", executor);
    WaitForEncryptionKeyInMemory.Params params = new WaitForEncryptionKeyInMemory.Params();
    params.universeUUID = taskParams().universeUUID;
    params.nodeAddress = HostAndPort.fromParts(node.cloudInfo.private_ip, node.masterRpcPort);
    params.nodeName = node.nodeName;
    WaitForEncryptionKeyInMemory task = createTask(WaitForEncryptionKeyInMemory.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create task to execute a Cluster CTL command against specific process
   *
   * @param node node for which the CTL command needs to be executed
   * @param processType, Master/TServer process type
   * @param command, actual command (start, stop, create)
   * @return SubTaskGroup
   */
  public SubTaskGroup createServerControlTask(
      NodeDetails node, UniverseDefinitionTaskBase.ServerType processType, String command) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleClusterServerCtl", executor);
    subTaskGroup.addSubTask(getServerControlTask(node, processType, command, 0));
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create task to check if a specific process is ready to serve requests on a given node.
   *
   * @param node node for which the check needs to be executed.
   * @param serverType server process type on the node to the check.
   * @param sleepTimeMs default sleep time if server does not support check for readiness.
   * @return SubTaskGroup
   */
  public SubTaskGroup createWaitForServerReady(
      NodeDetails node, ServerType serverType, int sleepTimeMs) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForServerReady", executor);
    WaitForServerReady.Params params = new WaitForServerReady.Params();
    params.universeUUID = taskParams().universeUUID;
    params.nodeName = node.nodeName;
    params.serverType = serverType;
    params.waitTimeMs = sleepTimeMs;
    WaitForServerReady task = createTask(WaitForServerReady.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create task to check if a specific process is caught up to other processes.
   *
   * @param node node for which the check needs to be executed.
   * @param serverType server process type on the node to the check.
   * @return SubTaskGroup
   */
  public SubTaskGroup createWaitForFollowerLagTask(NodeDetails node, ServerType serverType) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForLeaderBlacklistCompletion", executor);
    WaitForFollowerLag.Params params = new WaitForFollowerLag.Params();
    params.universeUUID = taskParams().universeUUID;
    params.serverType = serverType;
    params.node = node;
    params.nodeName = node.nodeName;
    WaitForFollowerLag task = createTask(WaitForFollowerLag.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create tasks to execute Cluster CTL command against specific process in parallel
   *
   * @param nodes set of nodes to issue control command in parallel.
   * @param processType, Master/TServer process type
   * @param command, actual command (start, stop, create)
   * @return SubTaskGroup
   */
  public SubTaskGroup createServerControlTasks(
      List<NodeDetails> nodes, UniverseDefinitionTaskBase.ServerType processType, String command) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleClusterServerCtl", executor);
    for (NodeDetails node : nodes) {
      subTaskGroup.addSubTask(getServerControlTask(node, processType, command, 0));
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  private AnsibleClusterServerCtl getServerControlTask(
      NodeDetails node,
      UniverseDefinitionTaskBase.ServerType processType,
      String command,
      int sleepAfterCmdMillis) {
    AnsibleClusterServerCtl.Params params = new AnsibleClusterServerCtl.Params();
    // Add the node name.
    params.nodeName = node.nodeName;
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    // Add the az uuid.
    params.azUuid = node.azUuid;
    // The service and the command we want to run.
    params.process = processType.toString().toLowerCase();
    params.command = command;
    params.sleepAfterCmdMills = sleepAfterCmdMillis;
    // Set the InstanceType
    params.instanceType = node.cloudInfo.instance_type;
    params.checkVolumesAttached = processType == ServerType.TSERVER && command.equals("start");
    // Create the Ansible task to get the server info.
    AnsibleClusterServerCtl task = createTask(AnsibleClusterServerCtl.class);
    task.initialize(params);
    return task;
  }

  /**
   * Create task to update the state of single node.
   *
   * @param node node for which we need to update the state
   * @param nodeState State into which these nodes will be transitioned.
   */
  public SubTaskGroup createSetNodeStateTask(NodeDetails node, NodeDetails.NodeState nodeState) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("SetNodeState", executor);
    SetNodeState.Params params = new SetNodeState.Params();
    params.azUuid = node.azUuid;
    params.universeUUID = taskParams().universeUUID;
    params.nodeName = node.nodeName;
    params.state = nodeState;
    SetNodeState task = createTask(SetNodeState.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create tasks to update the status of the nodes.
   *
   * @param nodes the set if nodes to be updated.
   * @param nodeStatus the status into which these nodes will be transitioned.
   * @return
   */
  public SubTaskGroup createSetNodeStatusTasks(
      Collection<NodeDetails> nodes, NodeStatus nodeStatus) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("SetNodeStatus", executor);
    for (NodeDetails node : nodes) {
      SetNodeStatus.Params params = new SetNodeStatus.Params();
      params.universeUUID = taskParams().universeUUID;
      params.azUuid = node.azUuid;
      params.nodeName = node.nodeName;
      params.nodeStatus = nodeStatus;
      SetNodeStatus task = createTask(SetNodeStatus.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create a task to update the swamper target file
   *
   * @param removeFile, flag to state if we want to remove the swamper or not
   */
  public void createSwamperTargetUpdateTask(boolean removeFile) {
    if (!config.getBoolean(MetricQueryHelper.PROMETHEUS_MANAGEMENT_ENABLED)) {
      return;
    }
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("SwamperTargetFileUpdate", executor);
    SwamperTargetsFileUpdate.Params params = new SwamperTargetsFileUpdate.Params();
    SwamperTargetsFileUpdate task = createTask(SwamperTargetsFileUpdate.class);
    params.universeUUID = taskParams().universeUUID;
    params.removeFile = removeFile;
    task.initialize(params);
    subTaskGroup.setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
  }

  /**
   * Create a task to create a table.
   *
   * @param tableType type of the table.
   * @param tableName name of the table.
   * @param tableDetails table options and related details.
   * @param ifNotExist create only if it does not exist.
   * @return
   */
  public SubTaskGroup createTableTask(
      TableType tableType, String tableName, TableDetails tableDetails, boolean ifNotExist) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("CreateTable", executor);
    CreateTable task = createTask(CreateTable.class);
    CreateTable.Params params = new CreateTable.Params();
    params.universeUUID = taskParams().universeUUID;
    params.tableType = tableType;
    params.tableName = tableName;
    params.tableDetails = tableDetails;
    params.ifNotExist = ifNotExist;
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Create a task to create write/read test table wor write/read metric and alert. */
  public SubTaskGroup createReadWriteTestTableTask(int numPartitions, boolean ifNotExist) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("CreateReadWriteTestTable");

    CreateTable task = createTask(CreateTable.class);

    ColumnDetails idColumn = new ColumnDetails();
    idColumn.isClusteringKey = true;
    idColumn.name = "id";
    idColumn.type = YQLDataType.SMALLINT;
    idColumn.sortOrder = SortOrder.ASC;

    TableDetails details = new TableDetails();
    details.tableName = "write_read_test";
    details.keyspace = SYSTEM_PLATFORM_DB;
    details.columns = new ArrayList<>();
    details.columns.add(idColumn);
    // Split at 0, 100, 200, 300 ... (numPartitions - 1) * 100
    if (numPartitions > 1) {
      details.splitValues =
          IntStream.range(0, numPartitions)
              .mapToObj(num -> String.valueOf(num * 100))
              .collect(Collectors.toList());
    }

    CreateTable.Params params = new CreateTable.Params();
    params.universeUUID = taskParams().universeUUID;
    params.tableType = TableType.PGSQL_TABLE_TYPE;
    params.tableName = details.tableName;
    params.tableDetails = details;
    params.ifNotExist = ifNotExist;

    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Create a task to delete a table.
   *
   * @param params The necessary parameters for dropping a table.
   */
  public SubTaskGroup createDeleteTableFromUniverseTask(DeleteTableFromUniverse.Params params) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("DeleteTableFromUniverse", executor);
    DeleteTableFromUniverse task = createTask(DeleteTableFromUniverse.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createWaitForServersTasks(Collection<NodeDetails> nodes, ServerType type) {
    return createWaitForServersTasks(
        nodes, type, config.getDuration("yb.wait_for_server_timeout") /* default timeout */);
  }

  /**
   * Create a task list to ping all servers until they are up.
   *
   * @param nodes : a collection of nodes that need to be pinged.
   * @param type : Master or tserver type server running on these nodes.
   * @param timeout : time to wait for each rpc call to the server.
   */
  public SubTaskGroup createWaitForServersTasks(
      Collection<NodeDetails> nodes, ServerType type, Duration timeout) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("WaitForServer", executor);
    for (NodeDetails node : nodes) {
      WaitForServer.Params params = new WaitForServer.Params();
      params.universeUUID = taskParams().universeUUID;
      params.nodeName = node.nodeName;
      params.serverType = type;
      params.serverWaitTimeoutMs = timeout.toMillis();
      WaitForServer task = createTask(WaitForServer.class);
      task.initialize(params);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  protected SubTaskGroup createUpdateMountedDisksTask(
      NodeDetails node, String currentInstanceType, DeviceInfo currentDeviceInfo) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("UpdateMountedDisks", executor);
    UpdateMountedDisks.Params params = new UpdateMountedDisks.Params();

    params.nodeName = node.nodeName;
    params.universeUUID = taskParams().universeUUID;
    params.azUuid = node.azUuid;
    params.instanceType = currentInstanceType;
    params.deviceInfo = currentDeviceInfo;

    UpdateMountedDisks updateMountedDisksTask = createTask(UpdateMountedDisks.class);
    updateMountedDisksTask.initialize(params);
    subTaskGroup.addSubTask(updateMountedDisksTask);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Creates a task to persist customized gflags to be used by server processes. */
  public SubTaskGroup updateGFlagsPersistTasks(
      Map<String, String> masterGFlags, Map<String, String> tserverGFlags) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("UpdateAndPersistGFlags", executor);
    UpdateAndPersistGFlags.Params params = new UpdateAndPersistGFlags.Params();
    params.universeUUID = taskParams().universeUUID;
    params.masterGFlags = masterGFlags;
    params.tserverGFlags = tserverGFlags;
    UpdateAndPersistGFlags task = createTask(UpdateAndPersistGFlags.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task to bulk import data from an s3 bucket into a given table.
   *
   * @param taskParams Info about the table and universe of the table to import into.
   */
  public SubTaskGroup createBulkImportTask(BulkImportParams taskParams) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("BulkImport", executor);
    BulkImport task = createTask(BulkImport.class);
    task.initialize(taskParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task delete the given node name from the univers.
   *
   * @param nodeName name of a node in the taskparams' uuid universe.
   */
  public SubTaskGroup createDeleteNodeFromUniverseTask(String nodeName) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("DeleteNode", executor);
    NodeTaskParams params = new NodeTaskParams();
    params.nodeName = nodeName;
    params.universeUUID = taskParams().universeUUID;
    DeleteNode task = createTask(DeleteNode.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Add or Remove Master process on the node
   *
   * @param node the node to add/remove master process on
   * @param isAdd whether Master is being added or removed.
   * @param subTask subtask type
   */
  public void createChangeConfigTask(
      NodeDetails node, boolean isAdd, UserTaskDetails.SubTaskGroupType subTask) {
    createChangeConfigTask(node, isAdd, subTask, false);
  }

  public void createChangeConfigTask(
      NodeDetails node,
      boolean isAdd,
      UserTaskDetails.SubTaskGroupType subTask,
      boolean useHostPort) {
    // Create a new task list for the change config so that it happens one by one.
    String subtaskGroupName =
        "ChangeMasterConfig(" + node.nodeName + ", " + (isAdd ? "add" : "remove") + ")";
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup(subtaskGroupName, executor);
    // Create the task params.
    ChangeMasterConfig.Params params = new ChangeMasterConfig.Params();
    // Set the azUUID
    params.azUuid = node.azUuid;
    // Add the node name.
    params.nodeName = node.nodeName;
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    // This is an add master.
    params.opType =
        isAdd ? ChangeMasterConfig.OpType.AddMaster : ChangeMasterConfig.OpType.RemoveMaster;
    params.useHostPort = useHostPort;
    // Create the task.
    ChangeMasterConfig changeConfig = createTask(ChangeMasterConfig.class);
    changeConfig.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(changeConfig);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    // Configure the user facing subtask for this task list.
    subTaskGroup.setSubTaskGroupType(subTask);
  }

  /**
   * Start T-Server process on the given node
   *
   * @param currentNode the node to operate upon
   * @param taskType Command start/stop
   * @return Subtask group
   */
  public SubTaskGroup createTServerTaskForNode(NodeDetails currentNode, String taskType) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleClusterServerCtl", executor);
    AnsibleClusterServerCtl.Params params = new AnsibleClusterServerCtl.Params();
    // Add the node name.
    params.nodeName = currentNode.nodeName;
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    // Add the az uuid.
    params.azUuid = currentNode.azUuid;
    // The service and the command we want to run.
    params.process = "tserver";
    params.command = taskType;
    // Set the InstanceType
    params.instanceType = currentNode.cloudInfo.instance_type;
    // Create the Ansible task to get the server info.
    AnsibleClusterServerCtl task = createTask(AnsibleClusterServerCtl.class);
    task.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Wait for Master Leader Election
   *
   * @return subtask group
   */
  public SubTaskGroup createWaitForMasterLeaderTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForMasterLeader", executor);
    WaitForMasterLeader task = createTask(WaitForMasterLeader.class);
    WaitForMasterLeader.Params params = new WaitForMasterLeader.Params();
    params.universeUUID = taskParams().universeUUID;
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  // Helper function to create a process update object.
  private UpdateNodeProcess getUpdateTaskProcess(
      String nodeName, ServerType processType, Boolean isAdd) {
    // Create the task params.
    UpdateNodeProcess.Params params = new UpdateNodeProcess.Params();
    params.processType = processType;
    params.isAdd = isAdd;
    params.universeUUID = taskParams().universeUUID;
    params.nodeName = nodeName;
    UpdateNodeProcess updateNodeProcess = createTask(UpdateNodeProcess.class);
    updateNodeProcess.initialize(params);
    return updateNodeProcess;
  }

  /**
   * Update the process state across all the given servers in Yugaware DB.
   *
   * @param servers : Set of nodes whose process state is to be updated.
   * @param processType : process type: master or tserver.
   * @param isAdd : true if the process is being added, false otherwise.
   */
  public void createUpdateNodeProcessTasks(
      Set<NodeDetails> servers, ServerType processType, Boolean isAdd) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("UpdateNodeProcess", executor);
    for (NodeDetails server : servers) {
      UpdateNodeProcess updateNodeProcess =
          getUpdateTaskProcess(server.nodeName, processType, isAdd);
      // Add it to the task list.
      subTaskGroup.addSubTask(updateNodeProcess);
    }
    subTaskGroup.setSubTaskGroupType(SubTaskGroupType.ConfigureUniverse);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
  }

  /**
   * Update the given node's process state in Yugaware DB,
   *
   * @param nodeName : name of the node where the process state is to be updated.
   * @param processType : process type: master or tserver.
   * @param isAdd : boolean signifying if the process is being added or removed.
   * @return The subtask group.
   */
  public SubTaskGroup createUpdateNodeProcessTask(
      String nodeName, ServerType processType, Boolean isAdd) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("UpdateNodeProcess", executor);
    UpdateNodeProcess updateNodeProcess = getUpdateTaskProcess(nodeName, processType, isAdd);
    // Add it to the task list.
    subTaskGroup.addSubTask(updateNodeProcess);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to start the masters and adds it to the task queue.
   *
   * @param nodes : a collection of nodes that need masters to be spawned.
   * @return The subtask group.
   */
  public SubTaskGroup createStartMasterTasks(Collection<NodeDetails> nodes) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleClusterServerCtl", executor);
    for (NodeDetails node : nodes) {
      AnsibleClusterServerCtl.Params params = new AnsibleClusterServerCtl.Params();
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // Add the az uuid.
      params.azUuid = node.azUuid;
      // The service and the command we want to run.
      params.process = "master";
      params.command = "start";
      params.placementUuid = node.placementUuid;
      // Set the InstanceType
      params.instanceType = node.cloudInfo.instance_type;
      // Create the Ansible task to get the server info.
      AnsibleClusterServerCtl task = createTask(AnsibleClusterServerCtl.class);
      task.initialize(params);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to stop the masters of the cluster and adds it to the task queue.
   *
   * @param nodes set of nodes to be stopped as master
   */
  public SubTaskGroup createStopMasterTasks(Collection<NodeDetails> nodes) {
    return createStopServerTasks(nodes, "master", false);
  }

  /**
   * Creates a task list to stop the tservers of the cluster and adds it to the task queue.
   *
   * @param nodes set of nodes to be stopped as master
   */
  public SubTaskGroup createStopServerTasks(
      Collection<NodeDetails> nodes, String serverType, boolean isForceDelete) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("AnsibleClusterServerCtl", executor);
    for (NodeDetails node : nodes) {
      AnsibleClusterServerCtl.Params params = new AnsibleClusterServerCtl.Params();
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // Add the az uuid.
      params.azUuid = node.azUuid;
      // The service and the command we want to run.
      params.process = serverType;
      params.command = "stop";
      // Set the InstanceType
      params.instanceType = node.cloudInfo.instance_type;
      params.isForceDelete = isForceDelete;
      // Create the Ansible task to get the server info.
      AnsibleClusterServerCtl task = createTask(AnsibleClusterServerCtl.class);
      task.initialize(params);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createTableBackupTask(BackupTableParams taskParams) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("BackupTable", executor, taskParams.ignoreErrors);
    BackupTable task = createTask(BackupTable.class);
    task.initialize(taskParams);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createTableBackupTaskYb(BackupTableParams taskParams) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("BackupTableYb", executor, taskParams.ignoreErrors);
    BackupTableYb task = createTask(BackupTableYb.class);
    task.initialize(taskParams);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createRestoreBackupTask(RestoreBackupParams taskParams) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("RestoreBackupYb", executor);
    RestoreBackupYb task = createTask(RestoreBackupYb.class);
    task.initialize(taskParams);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createDeleteBackupTasks(List<Backup> backups, UUID customerUUID) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("DeleteBackup", executor);
    for (Backup backup : backups) {
      DeleteBackup.Params params = new DeleteBackup.Params();
      params.backupUUID = backup.backupUUID;
      params.customerUUID = customerUUID;
      DeleteBackup task = createTask(DeleteBackup.class);
      task.initialize(params);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createDeleteBackupYbTasks(List<Backup> backups, UUID customerUUID) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("DeleteBackupYb", executor);
    for (Backup backup : backups) {
      DeleteBackupYb.Params params = new DeleteBackupYb.Params();
      params.backupUUID = backup.backupUUID;
      params.customerUUID = customerUUID;
      DeleteBackupYb task = createTask(DeleteBackupYb.class);
      task.initialize(params);
      subTaskGroup.addSubTask(task);
    }
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createEncryptedUniverseKeyBackupTask() {
    return createEncryptedUniverseKeyBackupTask((BackupTableParams) taskParams());
  }

  public SubTaskGroup createEncryptedUniverseKeyBackupTask(BackupTableParams params) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("BackupUniverseKeys", executor);
    BackupUniverseKeys task = createTask(BackupUniverseKeys.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createEncryptedUniverseKeyRestoreTask(BackupTableParams params) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("RestoreUniverseKeys", executor);
    RestoreUniverseKeys task = createTask(RestoreUniverseKeys.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createEncryptedUniverseKeyRestoreTaskYb(RestoreBackupParams params) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("RestoreUniverseKeysYb", executor);
    RestoreUniverseKeysYb task = createTask(RestoreUniverseKeysYb.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * It updates the source master addresses on the target universe cluster config for all xCluster
   * configs on the source universe.
   */
  public void createXClusterConfigUpdateMasterAddressesTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("XClusterConfigUpdateMasterAddresses", executor);
    List<XClusterConfig> xClusterConfigs =
        XClusterConfig.getBySourceUniverseUUID(taskParams().universeUUID);
    Set<UUID> updatedTargetUniverses = new HashSet<>();
    for (XClusterConfig config : xClusterConfigs) {
      UUID targetUniverseUUID = config.targetUniverseUUID;
      // Each target universe needs to be updated only once, even though there could be several
      // xCluster configs between each source and target universe pair.
      if (updatedTargetUniverses.contains(targetUniverseUUID)) {
        continue;
      }
      updatedTargetUniverses.add(targetUniverseUUID);

      XClusterConfigUpdateMasterAddresses.Params params =
          new XClusterConfigUpdateMasterAddresses.Params();
      // Set the target universe UUID to be told the new master addresses.
      params.universeUUID = targetUniverseUUID;
      // Set the source universe UUID to get the new master addresses.
      params.sourceUniverseUuid = taskParams().universeUUID;

      XClusterConfigUpdateMasterAddresses task =
          createTask(XClusterConfigUpdateMasterAddresses.class);
      task.initialize(params);
      task.setUserTaskUUID(userTaskUUID);
      // Add it to the task list.
      subTaskGroup.addSubTask(task);
    }
    if (subTaskGroup.getSubTaskCount() > 0) {
      getRunnableTask().addSubTaskGroup(subTaskGroup);
    }
  }

  /**
   * Creates a task list to manipulate the DNS record available for this universe.
   *
   * @param eventType the type of manipulation to do on the DNS records.
   * @param isForceDelete if this is a delete operation, set this to true to ignore errors
   * @param intent universe information.
   * @return subtask group
   */
  public SubTaskGroup createDnsManipulationTask(
      DnsManager.DnsCommandType eventType,
      boolean isForceDelete,
      UniverseDefinitionTaskParams.UserIntent intent) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("UpdateDnsEntry", executor);
    Provider p = Provider.getOrBadRequest(UUID.fromString(intent.provider));
    if (!p.getCloudCode().isHostedZoneEnabled()) {
      return subTaskGroup;
    }
    // TODO: shared constant with javascript land?
    String hostedZoneId = p.getHostedZoneId();
    if (hostedZoneId == null || hostedZoneId.isEmpty()) {
      return subTaskGroup;
    }
    ManipulateDnsRecordTask.Params params = new ManipulateDnsRecordTask.Params();
    params.universeUUID = taskParams().universeUUID;
    params.type = eventType;
    params.providerUUID = UUID.fromString(intent.provider);
    params.hostedZoneId = hostedZoneId;
    params.domainNamePrefix =
        String.format("%s.%s", intent.universeName, Customer.get(p.customerUUID).code);
    params.isForceDelete = isForceDelete;
    // Create the task to update DNS entries.
    ManipulateDnsRecordTask task = createTask(ManipulateDnsRecordTask.class);
    task.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task list to update the placement information by making a call to the master leader
   * and adds it to the task queue.
   *
   * @param blacklistNodes list of nodes which are being removed.
   */
  public SubTaskGroup createPlacementInfoTask(Collection<NodeDetails> blacklistNodes) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("UpdatePlacementInfo", executor);
    UpdatePlacementInfo.Params params = new UpdatePlacementInfo.Params();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    // Set the blacklist nodes if any are passed in.
    if (blacklistNodes != null && !blacklistNodes.isEmpty()) {
      Set<String> blacklistNodeNames = new HashSet<>();
      for (NodeDetails node : blacklistNodes) {
        blacklistNodeNames.add(node.nodeName);
      }
      params.blacklistNodes = blacklistNodeNames;
    }
    // Create the task to update placement info.
    UpdatePlacementInfo task = createTask(UpdatePlacementInfo.class);
    task.initialize(params);
    task.setUserTaskUUID(userTaskUUID);
    // Add it to the task list.
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task to move the data out of blacklisted servers.
   *
   * @return the created task group.
   */
  public SubTaskGroup createWaitForDataMoveTask() {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("WaitForDataMove", executor);
    WaitForDataMove.Params params = new WaitForDataMove.Params();
    params.universeUUID = taskParams().universeUUID;
    // Create the task.
    WaitForDataMove waitForMove = createTask(WaitForDataMove.class);
    waitForMove.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(waitForMove);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createWaitForLeaderBlacklistCompletionTask(int waitTimeMs) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForLeaderBlacklistCompletion", executor);
    WaitForLeaderBlacklistCompletion.Params params = new WaitForLeaderBlacklistCompletion.Params();
    params.universeUUID = taskParams().universeUUID;
    params.waitTimeMs = waitTimeMs;
    // Create the task.
    WaitForLeaderBlacklistCompletion leaderBlacklistCompletion =
        createTask(WaitForLeaderBlacklistCompletion.class);
    leaderBlacklistCompletion.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(leaderBlacklistCompletion);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /** Creates a task to wait for leaders to be on preferred regions only. */
  public void createWaitForLeadersOnPreferredOnlyTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForLeadersOnPreferredOnly", executor);
    WaitForLeadersOnPreferredOnly.Params params = new WaitForLeadersOnPreferredOnly.Params();
    params.universeUUID = taskParams().universeUUID;
    // Create the task.
    WaitForLeadersOnPreferredOnly waitForLeadersOnPreferredOnly =
        createTask(WaitForLeadersOnPreferredOnly.class);
    waitForLeadersOnPreferredOnly.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(waitForLeadersOnPreferredOnly);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    // Set the subgroup task type.
    subTaskGroup.setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.WaitForDataMigration);
  }

  /**
   * Creates a task to move the data onto any lesser loaded servers.
   *
   * @return the created task group.
   */
  public SubTaskGroup createWaitForLoadBalanceTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("WaitForLoadBalance", executor);
    WaitForLoadBalance.Params params = new WaitForLoadBalance.Params();
    params.universeUUID = taskParams().universeUUID;
    // Create the task.
    WaitForLoadBalance waitForLoadBalance = createTask(WaitForLoadBalance.class);
    waitForLoadBalance.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(waitForLoadBalance);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  /**
   * Creates a task to remove a node from blacklist on server.
   *
   * @param nodes The nodes that have to be removed from the blacklist.
   * @param isAdd true if the node are added to server blacklist, else removed.
   * @param isLeaderBlacklist true if we are leader blacklisting the node
   * @return the created task group.
   */
  public SubTaskGroup createModifyBlackListTask(
      Collection<NodeDetails> nodes, boolean isAdd, boolean isLeaderBlacklist) {
    if (isAdd) {
      return createModifyBlackListTask(nodes, null, isLeaderBlacklist);
    }
    return createModifyBlackListTask(null, nodes, isLeaderBlacklist);
  }

  /**
   * Creates a task to add/remove nodes from blacklist on server.
   *
   * @param addNodes The nodes that have to be added to the blacklist.
   * @param removeNodes The nodes that have to be removed from the blacklist.
   * @param isLeaderBlacklist true if we are leader blacklisting the node
   */
  public SubTaskGroup createModifyBlackListTask(
      Collection<NodeDetails> addNodes,
      Collection<NodeDetails> removeNodes,
      boolean isLeaderBlacklist) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup("ModifyBlackList");
    ModifyBlackList.Params params = new ModifyBlackList.Params();
    params.universeUUID = taskParams().universeUUID;
    params.addNodes = addNodes;
    params.removeNodes = removeNodes;
    params.isLeaderBlacklist = isLeaderBlacklist;
    // Create the task.
    ModifyBlackList modifyBlackList = createTask(ModifyBlackList.class);
    modifyBlackList.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(modifyBlackList);
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  // Subtask to update gflags in memory.
  public SubTaskGroup createSetFlagInMemoryTasks(
      Collection<NodeDetails> nodes,
      ServerType serverType,
      boolean force,
      Map<String, String> gflags,
      boolean updateMasterAddrs) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("InMemoryGFlagUpdate", executor);
    for (NodeDetails node : nodes) {
      // Create the task params.
      SetFlagInMemory.Params params = new SetFlagInMemory.Params();
      // Add the node name.
      params.nodeName = node.nodeName;
      // Add the universe uuid.
      params.universeUUID = taskParams().universeUUID;
      // The server type for the flag.
      params.serverType = serverType;
      // If the flags need to be force updated.
      params.force = force;
      // The flags to update.
      params.gflags = gflags;
      // If only master addresses need to be updated.
      params.updateMasterAddrs = updateMasterAddrs;

      // Create the task.
      SetFlagInMemory setFlag = createTask(SetFlagInMemory.class);
      setFlag.initialize(params);
      // Add it to the task list.
      subTaskGroup.addSubTask(setFlag);
    }
    // Add the task list to the task queue.
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  // Check if the node present in taskParams has a backing instance alive on the IaaS.
  public boolean instanceExists(NodeTaskParams taskParams) {
    Optional<Boolean> optional = instanceExists(taskParams, null);
    return optional.isPresent();
  }

  // It returns 3 states - empty for not found, false for not matching and true for matching.
  public Optional<Boolean> instanceExists(
      NodeTaskParams taskParams, Map<String, String> expectedTags) {
    NodeManager nodeManager = Play.current().injector().instanceOf(NodeManager.class);
    ShellResponse response =
        nodeManager.nodeCommand(NodeManager.NodeCommandType.List, taskParams).processErrors();
    if (Strings.isNullOrEmpty(response.message)) {
      // Instance does not exist.
      return Optional.empty();
    }
    if (MapUtils.isEmpty(expectedTags)) {
      return Optional.of(true);
    }
    JsonNode jsonNode = Json.parse(response.message);
    if (jsonNode.isArray()) {
      jsonNode = jsonNode.get(0);
    }
    long matchCount =
        Streams.stream(jsonNode.fields())
            .filter(
                e -> {
                  log.info(
                      "Node: {}, Key: {}, Value: {}",
                      taskParams.nodeName,
                      e.getKey(),
                      e.getValue());
                  String expectedTagValue = expectedTags.get(e.getKey());
                  return expectedTagValue != null && expectedTagValue.equals(e.getValue().asText());
                })
            .count();
    log.info("Expected tags: {}", expectedTags);
    return Optional.of(matchCount == expectedTags.size());
  }

  // Perform preflight checks on the given node.
  public String performPreflightCheck(
      Cluster cluster,
      NodeDetails currentNode,
      @Nullable UUID rootCA,
      @Nullable UUID clientRootCA) {
    if (cluster.userIntent.providerType != com.yugabyte.yw.commissioner.Common.CloudType.onprem) {
      return null;
    }
    NodeTaskParams preflightTaskParams = new NodeTaskParams();
    UserIntent userIntent = cluster.userIntent;
    preflightTaskParams.nodeName = currentNode.nodeName;
    preflightTaskParams.deviceInfo = userIntent.deviceInfo;
    preflightTaskParams.azUuid = currentNode.azUuid;
    preflightTaskParams.universeUUID = taskParams().universeUUID;
    preflightTaskParams.rootCA = rootCA;
    preflightTaskParams.clientRootCA = clientRootCA;
    UniverseTaskParams.CommunicationPorts.exportToCommunicationPorts(
        preflightTaskParams.communicationPorts, currentNode);
    preflightTaskParams.extraDependencies.installNodeExporter =
        taskParams().extraDependencies.installNodeExporter;
    // Create the process to fetch information about the node from the cloud provider.
    NodeManager nodeManager = Play.current().injector().instanceOf(NodeManager.class);
    log.info("Running preflight checks for node {}.", preflightTaskParams.nodeName);
    ShellResponse response =
        nodeManager.nodeCommand(NodeManager.NodeCommandType.Precheck, preflightTaskParams);
    if (response.code == 0) {
      JsonNode responseJson = Json.parse(response.message);
      for (JsonNode nodeContent : responseJson) {
        if (!nodeContent.isBoolean() || !nodeContent.asBoolean()) {
          String errString =
              "Failed preflight checks for node "
                  + preflightTaskParams.nodeName
                  + ":\n"
                  + response.message;
          log.error(errString);
          return response.message;
        }
      }
    }
    return null;
  }

  private boolean isServerAlive(NodeDetails node, ServerType server, String masterAddrs) {
    YBClientService ybService = Play.current().injector().instanceOf(YBClientService.class);

    Universe universe = Universe.getOrBadRequest(taskParams().universeUUID);
    String certificate = universe.getCertificateNodetoNode();
    YBClient client = ybService.getClient(masterAddrs, certificate);
    try {
      HostAndPort hp =
          HostAndPort.fromParts(
              node.cloudInfo.private_ip,
              server == ServerType.MASTER ? node.masterRpcPort : node.tserverRpcPort);
      return client.waitForServer(hp, 5000);
    } finally {
      ybService.closeClient(client, masterAddrs);
    }
  }

  public boolean isMasterAliveOnNode(NodeDetails node, String masterAddrs) {
    if (!node.isMaster) {
      return false;
    }
    return isServerAlive(node, ServerType.MASTER, masterAddrs);
  }

  public boolean isTserverAliveOnNode(NodeDetails node, String masterAddrs) {
    return isServerAlive(node, ServerType.TSERVER, masterAddrs);
  }

  // Helper API to update the db for the node with the given state.
  public void setNodeState(String nodeName, NodeDetails.NodeState state) {
    // Persist the desired node information into the DB.
    UniverseUpdater updater =
        nodeStateUpdater(
            taskParams().universeUUID, nodeName, NodeStatus.builder().nodeState(state).build());
    saveUniverseDetails(updater);
  }

  // Return list of nodeNames from the given set of node details.
  public String nodeNames(Collection<NodeDetails> nodes) {
    String nodeNames = "";
    for (NodeDetails node : nodes) {
      nodeNames += node.nodeName + ",";
    }
    return nodeNames.substring(0, nodeNames.length() - 1);
  }

  /** Disable the loadbalancer to not move data. Used during rolling upgrades. */
  public SubTaskGroup createLoadBalancerStateChangeTask(boolean enable) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("LoadBalancerStateChange", executor);
    LoadBalancerStateChange.Params params = new LoadBalancerStateChange.Params();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    params.enable = enable;
    LoadBalancerStateChange task = createTask(LoadBalancerStateChange.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createResetUniverseVersionTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("ResetUniverseVersion", executor);
    ResetUniverseVersion task = createTask(ResetUniverseVersion.class);
    task.initialize(taskParams());
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  // Update the Universe's 'backupInProgress' flag to new state.
  private void updateBackupState(boolean state) {
    UniverseUpdater updater =
        new UniverseUpdater() {
          @Override
          public void run(Universe universe) {
            UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
            universeDetails.backupInProgress = state;
            universe.setUniverseDetails(universeDetails);
          }
        };
    if (state) {
      // New state is to set backupInProgress to true.
      // This method increments universe version if HA is enabled.
      saveUniverseDetails(updater);
    } else {
      // New state is to set backupInProgress to false.
      // This method simply updates the backupInProgress without changing the universe version.
      // This is called at the end of backup to release the universe for other tasks.
      Universe.saveDetails(taskParams().universeUUID, updater, false);
    }
  }

  // Update the Universe's 'backupInProgress' flag to new state.
  // It throws exception if the universe is already being locked by another task.
  public void lockedUpdateBackupState(boolean newState) {
    checkNotNull(taskParams().universeUUID, "Universe UUID must be set.");
    if (Universe.getOrBadRequest(taskParams().universeUUID).getUniverseDetails().backupInProgress
        == newState) {
      if (newState) {
        throw new IllegalStateException("A backup for this universe is already in progress.");
      } else {
        return;
      }
    }
    updateBackupState(newState);
  }

  /**
   * Whether to increment the universe/cluster config version. Skip incrementing version if the task
   * updating the universe metadata is create/destroy/pause/resume universe. Also, skip incrementing
   * version if task must manually handle version incrementing (such as in the case of XCluster).
   *
   * @return true if we should increment the version, false otherwise
   */
  protected boolean shouldIncrementVersion() {

    final VersionCheckMode mode =
        runtimeConfigFactory
            .forUniverse(getUniverse())
            .getEnum(VersionCheckMode.class, "yb.universe_version_check_mode");
    if (mode == VersionCheckMode.NEVER) {
      return false;
    }

    if (mode == VersionCheckMode.HA_ONLY && !HighAvailabilityConfig.get().isPresent()) {
      return false;
    }

    // For create/destroy/pause/resume operations, do not attempt to bump up
    // the cluster config version on the leader master because the cluster
    // and the leader master may not be available at the time we are attempting to do this.
    if (userTaskUUID == null) {
      return false;
    }

    TaskInfo taskInfo = TaskInfo.get(userTaskUUID);
    if (taskInfo == null) {
      return false;
    }

    TaskType taskType = taskInfo.getTaskType();
    return !(taskType == TaskType.CreateUniverse
        || taskType == TaskType.CreateKubernetesUniverse
        || taskType == TaskType.DestroyUniverse
        || taskType == TaskType.DestroyKubernetesUniverse
        || taskType == TaskType.PauseUniverse
        || taskType == TaskType.ResumeUniverse
        || taskType == TaskType.CreateXClusterConfig
        || taskType == TaskType.EditXClusterConfig
        || taskType == TaskType.SyncXClusterConfig
        || taskType == TaskType.DeleteXClusterConfig);
  }

  // TODO: Use of synchronized in static scope! Looks suspicious.
  //  Use of transactions may be better.
  private static synchronized int getClusterConfigVersion(Universe universe) {
    final YBClientService ybService = Play.current().injector().instanceOf(YBClientService.class);
    final String hostPorts = universe.getMasterAddresses();
    final String certificate = universe.getCertificateNodetoNode();
    int version;
    YBClient client = ybService.getClient(hostPorts, certificate);
    try {
      version = client.getMasterClusterConfig().getConfig().getVersion();
    } catch (Exception e) {
      log.error("Error occurred retrieving cluster config version", e);
      throw new RuntimeException("Error incrementing cluster config version", e);
    } finally {
      ybService.closeClient(client, hostPorts);
    }
    return version;
  }

  // TODO: Use of synchronized in static scope! Looks suspicious.
  //  Use of transactions may be better.
  private static synchronized boolean versionsMatch(UUID universeUUID) {
    Universe universe = Universe.getOrBadRequest(universeUUID);
    final int clusterConfigVersion = UniverseTaskBase.getClusterConfigVersion(universe);

    // For backwards compatibility (see V56__Alter_Universe_Version.sql)
    if (universe.version == -1) {
      universe.version = clusterConfigVersion;
      log.info(
          "Updating version for universe {} from -1 to cluster config version {}",
          universeUUID,
          universe.version);
      universe.save();
    }

    return universe.version == clusterConfigVersion;
  }

  /**
   * checkUniverseVersion
   *
   * @param universeUUID
   *     <p>Check that the universe version in the Platform database matches the one in the cluster
   *     config on the yugabyte db master. A mismatch could indicate one of two issues: 1. Multiple
   *     Platform replicas in a HA config are operating on the universe and (async) replication has
   *     failed to sychronize Platform db state correctly across different Platforms. We want to
   *     flag this case. 2. Manual yb-admin operations on the cluster have bumped up the database
   *     cluster config version. This is not necessarily always a problem, so we choose to ignore
   *     this case for now. When we get to a point where manual yb-admin operations are never
   *     needed, we can consider flagging this case. For now, we will let the universe version on
   *     Platform and the cluster config version on the master diverge.
   * @param mode
   */
  private static void checkUniverseVersion(UUID universeUUID, VersionCheckMode mode) {
    if (mode == VersionCheckMode.NEVER) {
      return;
    }

    if (mode == VersionCheckMode.HA_ONLY && !HighAvailabilityConfig.get().isPresent()) {
      log.debug("Skipping cluster config version check for universe {}", universeUUID);
      return;
    }

    if (!versionsMatch(universeUUID)) {
      throw new RuntimeException("Universe version does not match cluster config version");
    }
  }

  protected void checkUniverseVersion() {
    UniverseTaskBase.checkUniverseVersion(
        taskParams().universeUUID,
        runtimeConfigFactory
            .forUniverse(getUniverse())
            .getEnum(VersionCheckMode.class, "yb.universe_version_check_mode"));
  }

  /** Increment the cluster config version */
  private static synchronized void incrementClusterConfigVersion(UUID universeUUID) {
    Universe universe = Universe.getOrBadRequest(universeUUID);
    YBClientService ybService = Play.current().injector().instanceOf(YBClientService.class);
    final String hostPorts = universe.getMasterAddresses();
    String certificate = universe.getCertificateNodetoNode();
    YBClient client = ybService.getClient(hostPorts, certificate);
    try {
      int version = universe.version;
      ModifyClusterConfigIncrementVersion modifyConfig =
          new ModifyClusterConfigIncrementVersion(client, version);
      int newVersion = modifyConfig.incrementVersion();
      log.info(
          "Updated cluster config version for universe {} from {} to {}",
          universeUUID,
          version,
          newVersion);
    } catch (Exception e) {
      log.error(
          "Error occurred incrementing cluster config version for universe " + universeUUID, e);
      throw new RuntimeException("Error incrementing cluster config version", e);
    } finally {
      ybService.closeClient(client, hostPorts);
    }
  }

  /**
   * Run universe updater and increment the cluster config version
   *
   * @param updater the universe updater to run
   * @return the updated universe
   */
  protected static Universe saveUniverseDetails(
      UUID universeUUID, boolean shouldIncrementVersion, UniverseUpdater updater) {
    Universe.UNIVERSE_KEY_LOCK.acquireLock(universeUUID);
    try {
      if (shouldIncrementVersion) {
        incrementClusterConfigVersion(universeUUID);
      }
      return Universe.saveDetails(universeUUID, updater, shouldIncrementVersion);
    } finally {
      Universe.UNIVERSE_KEY_LOCK.releaseLock(universeUUID);
    }
  }

  protected Universe saveUniverseDetails(UniverseUpdater updater) {
    return UniverseTaskBase.saveUniverseDetails(
        taskParams().universeUUID, shouldIncrementVersion(), updater);
  }

  protected void preTaskActions() {
    Universe universe = Universe.getOrBadRequest(taskParams().universeUUID);
    preTaskActions(universe);
  }

  // Use this if it is already in transaction or the field changes are not yet written to the DB.
  protected void preTaskActions(Universe universe) {
    HealthChecker healthChecker = Play.current().injector().instanceOf(HealthChecker.class);
    UniverseDefinitionTaskParams details = universe.getUniverseDetails();
    if ((details != null) && details.updateInProgress) {
      log.debug("Cancelling any active health-checks for universe {}", universe.universeUUID);
      healthChecker.cancelHealthCheck(universe.universeUUID);
    }
  }
}
