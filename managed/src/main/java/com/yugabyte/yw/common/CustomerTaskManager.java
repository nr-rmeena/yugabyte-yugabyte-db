// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static com.yugabyte.yw.models.CustomerTask.TargetType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.tasks.subtasks.LoadBalancerStateChange;
import com.yugabyte.yw.forms.RestoreBackupParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.common.services.YBClientService;
import org.yb.client.ChangeLoadBalancerStateResponse;
import org.yb.client.YBClient;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.ScheduleTask;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import io.ebean.Ebean;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.Play;

@Singleton
public class CustomerTaskManager {

  public static final Logger LOG = LoggerFactory.getLogger(CustomerTaskManager.class);
  private static final List<TaskType> LOAD_BALANCER_TASK_TYPES =
      Arrays.asList(
          TaskType.RestoreBackup,
          TaskType.BackupUniverse,
          TaskType.MultiTableBackup,
          TaskType.CreateBackup);
  private static final String ALTER_LOAD_BALANCER = "alterLoadBalancer";
  @Inject YBClientService ybService;

  private void setTaskError(TaskInfo taskInfo) {
    taskInfo.setTaskState(TaskInfo.State.Failure);
    JsonNode jsonNode = taskInfo.getTaskDetails();
    if (jsonNode instanceof ObjectNode) {
      ObjectNode details = (ObjectNode) jsonNode;
      JsonNode errNode = details.get("errorString");
      if (errNode == null || errNode.isNull()) {
        details.put("errorString", "Platform restarted.");
      }
    }
  }

  public void failPendingTask(CustomerTask customerTask, TaskInfo taskInfo) {
    try {
      // Mark each subtask as a failure if it is not completed.
      taskInfo
          .getIncompleteSubTasks()
          .forEach(
              subtask -> {
                setTaskError(subtask);
                subtask.save();
              });

      if (LOAD_BALANCER_TASK_TYPES.contains(taskInfo.getTaskType())) {
        Boolean isLoadBalanceAltered = false;
        JsonNode node = taskInfo.getTaskDetails();
        if (node.has(ALTER_LOAD_BALANCER)) {
          isLoadBalanceAltered = node.path(ALTER_LOAD_BALANCER).asBoolean(false);
        }
        Optional<Universe> optUniv = Universe.maybeGet(customerTask.getTargetUUID());
        if (optUniv.isPresent() && isLoadBalanceAltered) {
          enableLoadBalancer(optUniv.get());
        }
      }

      UUID taskUUID = taskInfo.getTaskUUID();
      ScheduleTask scheduleTask = ScheduleTask.fetchByTaskUUID(taskUUID);
      if (scheduleTask != null) {
        scheduleTask.setCompletedTime();
      }

      // Use isUniverseTarget() instead of directly comparing with Universe type because some
      // targets like Cluster, Node are Universe targets.
      boolean unlockUniverse = customerTask.getTarget().isUniverseTarget();
      if (customerTask.getTarget().equals(TargetType.Backup)) {
        // Backup is not universe target.

        CustomerTask.TaskType type = customerTask.getType();
        if (CustomerTask.TaskType.Create.equals(type)) {
          // Make transition state false for inProgress backups
          List<Backup> backupList = Backup.fetchAllBackupsByTaskUUID(taskUUID);
          backupList
              .stream()
              .filter(backup -> backup.state.equals(Backup.BackupState.InProgress))
              .forEach(backup -> backup.transitionState(Backup.BackupState.Failed));
          unlockUniverse = true;
        } else if (CustomerTask.TaskType.Delete.equals(type)) {
          // NOOP because Delete does not lock Universe.
        } else if (CustomerTask.TaskType.Restore.equals(type)) {
          // Restore only locks the Universe but does not set backupInProgress flag.
          unlockUniverse = true;
        }
      }

      if (unlockUniverse) {
        // Unlock the universe for future operations.
        Universe.maybeGet(customerTask.getTargetUUID())
            .ifPresent(
                u -> {
                  UniverseDefinitionTaskParams details = u.getUniverseDetails();
                  if (details.backupInProgress || details.updateInProgress) {
                    // Create the update lambda.
                    Universe.UniverseUpdater updater =
                        universe -> {
                          UniverseDefinitionTaskParams universeDetails =
                              universe.getUniverseDetails();
                          universeDetails.backupInProgress = false;
                          universeDetails.updateInProgress = false;
                          universe.setUniverseDetails(universeDetails);
                        };

                    Universe.saveDetails(customerTask.getTargetUUID(), updater, false);
                    LOG.debug("Unlocked universe {}.", customerTask.getTargetUUID());
                  }
                });
      }

      // Mark task as a failure after the universe is unlocked.
      if (TaskInfo.INCOMPLETE_STATES.contains(taskInfo.getTaskState())) {
        setTaskError(taskInfo);
        taskInfo.save();
      }
      // Mark customer task as completed.
      // Customer task is marked completed after the task state is updated in TaskExecutor.
      // Moreover, this method has an internal guard to set the completion time only if it is null.
      customerTask.markAsCompleted();
    } catch (Exception e) {
      LOG.error(String.format("Error encountered failing task %s", customerTask.getTaskUUID()), e);
    }
  }

  public void failAllPendingTasks() {
    LOG.info("Failing incomplete tasks...");
    try {
      String incompleteStates =
          TaskInfo.INCOMPLETE_STATES
              .stream()
              .map(Objects::toString)
              .collect(Collectors.joining("','"));
      // Retrieve all incomplete customer tasks or task in incomplete state. Task state update and
      // customer completion time update are not transactional.
      String query =
          "SELECT ti.uuid AS task_uuid, ct.id AS customer_task_id "
              + "FROM task_info ti, customer_task ct "
              + "WHERE ti.uuid = ct.task_uuid "
              + "AND (ct.completion_time IS NULL "
              + "OR ti.task_state IN ('"
              + incompleteStates
              + "'))";
      // TODO use Finder.
      Ebean.createSqlQuery(query)
          .findList()
          .forEach(
              row -> {
                TaskInfo taskInfo = TaskInfo.get(row.getUUID("task_uuid"));
                CustomerTask customerTask = CustomerTask.get(row.getLong("customer_task_id"));
                failPendingTask(customerTask, taskInfo);
              });
    } catch (Exception e) {
      LOG.error("Encountered error failing pending tasks", e);
    }
  }

  private void enableLoadBalancer(Universe universe) {
    ChangeLoadBalancerStateResponse resp = null;
    YBClient client = null;
    String masterHostPorts = universe.getMasterAddresses();
    String certificate = universe.getCertificateNodetoNode();
    try {
      client = ybService.getClient(masterHostPorts, certificate);
      resp = client.changeLoadBalancerState(true);
    } catch (Exception e) {
      LOG.error(
          "Setting load balancer to state true has failed for universe: "
              + universe.getUniverseUUID());
    } finally {
      ybService.closeClient(client, masterHostPorts);
    }
    if (resp != null && resp.hasError()) {
      LOG.error(
          "Setting load balancer to state true has failed for universe: "
              + universe.getUniverseUUID());
    }
  }
}
