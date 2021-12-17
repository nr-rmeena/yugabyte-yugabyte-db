// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static com.yugabyte.yw.common.ShellResponse.ERROR_CODE_EXECUTION_CANCELLED;
import static com.yugabyte.yw.common.ShellResponse.ERROR_CODE_SUCCESS;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.typesafe.config.Config;
import com.yugabyte.yw.commissioner.TaskExecutor.RunnableTask;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.PlatformExecutorFactory;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TableManager;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.Universe.UniverseUpdater;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeStatus;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import play.Application;
import play.api.Play;
import play.libs.Json;

@Slf4j
public abstract class AbstractTaskBase implements ITask {

  private static final String SLEEP_DISABLED_PATH = "yb.tasks.disabled_timeouts";

  // The params for this task.
  protected ITaskParams taskParams;

  // The threadpool on which the tasks are executed.
  protected ExecutorService executor;

  // The sequence of task lists that should be executed.
  protected SubTaskGroupQueue subTaskGroupQueue;

  // The UUID of the top-level user-facing task at the top of Task tree. Eg. CreateUniverse, etc.
  protected UUID userTaskUUID;

  // A field used to send additional information with prometheus metric associated with this task
  public String taskInfo = "";

  protected final Application application;
  protected final play.Environment environment;
  protected final Config config;
  protected final ConfigHelper configHelper;
  protected final RuntimeConfigFactory runtimeConfigFactory;
  protected final MetricService metricService;
  protected final AlertConfigurationService alertConfigurationService;
  protected final YBClientService ybService;
  protected final TableManager tableManager;
  private final PlatformExecutorFactory platformExecutorFactory;
  private final TaskExecutor taskExecutor;

  @Inject
  protected AbstractTaskBase(BaseTaskDependencies baseTaskDependencies) {
    this.application = baseTaskDependencies.getApplication();
    this.environment = baseTaskDependencies.getEnvironment();
    this.config = baseTaskDependencies.getConfig();
    this.configHelper = baseTaskDependencies.getConfigHelper();
    this.runtimeConfigFactory = baseTaskDependencies.getRuntimeConfigFactory();
    this.metricService = baseTaskDependencies.getMetricService();
    this.alertConfigurationService = baseTaskDependencies.getAlertConfigurationService();
    this.ybService = baseTaskDependencies.getYbService();
    this.tableManager = baseTaskDependencies.getTableManager();
    this.platformExecutorFactory = baseTaskDependencies.getExecutorFactory();
    this.taskExecutor = baseTaskDependencies.getTaskExecutor();
  }

  protected ITaskParams taskParams() {
    return taskParams;
  }

  @Override
  public void initialize(ITaskParams params) {
    this.taskParams = params;
  }

  @Override
  public String getName() {
    return this.getClass().getSimpleName();
  }

  @Override
  public JsonNode getTaskDetails() {
    return Json.toJson(taskParams);
  }

  @Override
  public String toString() {
    return getName() + " : details=" + getTaskDetails();
  }

  @Override
  public abstract void run();

  @Override
  public void terminate() {
    if (executor != null && !executor.isShutdown()) {
      MoreExecutors.shutdownAndAwaitTermination(executor, 5, TimeUnit.MINUTES);
    }
  }

  // Create an task pool which can handle an unbounded number of tasks, while using an initial set
  // of threads which get spawned upto TASK_THREADS limit.
  public void createThreadpool() {
    ThreadFactory namedThreadFactory =
        new ThreadFactoryBuilder().setNameFormat("TaskPool-" + getName() + "-%d").build();
    executor = platformExecutorFactory.createExecutor("task", namedThreadFactory);
  }

  @Override
  public void setUserTaskUUID(UUID userTaskUUID) {
    this.userTaskUUID = userTaskUUID;
  }

  /** @param response : ShellResponse object */
  public void processShellResponse(ShellResponse response) {
    if (response.code == ERROR_CODE_EXECUTION_CANCELLED) {
      throw new CancellationException((response.message != null) ? response.message : "error");
    }
    if (response.code != ERROR_CODE_SUCCESS) {
      throw new RuntimeException((response.message != null) ? response.message : "error");
    }
  }

  /**
   * We would try to parse the shell response message as JSON and return JsonNode
   *
   * @param response: ShellResponse object
   * @return JsonNode: Json formatted shell response message
   */
  public JsonNode parseShellResponseAsJson(ShellResponse response) {
    return Util.convertStringToJson(response.message);
  }

  public UniverseUpdater nodeStateUpdater(
      final UUID universeUUID, final String nodeName, final NodeStatus nodeStatus) {
    UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          NodeDetails node = universe.getNode(nodeName);
          if (node == null) {
            return;
          }
          NodeStatus currentStatus = NodeStatus.fromNode(node);
          log.info(
              "Changing node {} state from {} to {} in universe {}.",
              nodeName,
              currentStatus,
              nodeStatus,
              universeUUID);
          nodeStatus.fillNodeStates(node);
          if (nodeStatus.getNodeState() == NodeDetails.NodeState.Decommissioned) {
            node.cloudInfo.private_ip = null;
            node.cloudInfo.public_ip = null;
          }

          // Update the node details.
          universeDetails.nodeDetailsSet.add(node);
          universe.setUniverseDetails(universeDetails);
        };
    return updater;
  }

  /**
   * Creates task with appropriate dependency injection
   *
   * @param taskClass task class
   * @return Task instance with injected dependencies
   */
  public static <T> T createTask(Class<T> taskClass) {
    return Play.current().injector().instanceOf(taskClass);
  }

  public int getSleepMultiplier() {
    try {
      return config.getBoolean(SLEEP_DISABLED_PATH) ? 0 : 1;
    } catch (Exception e) {
      return 1;
    }
  }

  protected TaskExecutor getTaskExecutor() {
    return taskExecutor;
  }

  // Returns the RunnableTask instance to which SubTaskGroup instances can be added and run.
  // TODO Use this helper method instead of instantiating SubTaskGroupQueue in the task.
  protected RunnableTask getRunnableTask() {
    return getTaskExecutor().getRunnableTask(userTaskUUID);
  }

  // Returns a SubTaskGroup to which subtasks can be added.
  // TODO Use this helper method instead of instantiating SubTaskGroup in the task.
  protected SubTaskGroup createSubTaskGroup(String name) {
    SubTaskGroup subTaskGroup = getTaskExecutor().createSubTaskGroup(name);
    subTaskGroup.setSubTaskExecutor(executor);
    return subTaskGroup;
  }

  @Override
  public boolean isAbortable() {
    return false;
  }
}
