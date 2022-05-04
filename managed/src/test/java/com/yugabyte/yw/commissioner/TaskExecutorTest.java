// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.commissioner.TaskExecutor.RunnableTask;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.commissioner.TaskExecutor.TaskExecutionListener;
import com.yugabyte.yw.common.PlatformGuiceApplicationBaseTest;
import com.yugabyte.yw.common.ha.PlatformReplicationManager;
import com.yugabyte.yw.common.password.RedactingService;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import kamon.instrumentation.play.GuiceModule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.modules.swagger.SwaggerModule;
import play.test.Helpers;

@RunWith(MockitoJUnitRunner.class)
public class TaskExecutorTest extends PlatformGuiceApplicationBaseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private TaskExecutor taskExecutor;

  @Override
  protected Application provideApplication() {
    return configureApplication(
            new GuiceApplicationBuilder()
                .disable(SwaggerModule.class)
                .disable(GuiceModule.class)
                .configure((Map) Helpers.inMemoryDatabase())
                .overrides(
                    bind(PlatformReplicationManager.class)
                        .toInstance(mock(PlatformReplicationManager.class)))
                .overrides(
                    bind(ExecutorServiceProvider.class).to(DefaultExecutorServiceProvider.class)))
        .build();
  }

  private TaskInfo waitForTask(UUID taskUUID) {
    long elapsedTimeMs = 0;
    while (taskExecutor.isTaskRunning(taskUUID) && elapsedTimeMs < 20000) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
      elapsedTimeMs += 100;
    }
    if (taskExecutor.isTaskRunning(taskUUID)) {
      fail("Task " + taskUUID + " did not complete in time");
    }
    return TaskInfo.getOrBadRequest(taskUUID);
  }

  private ITask mockTaskCommon() {
    JsonNode node = mapper.createObjectNode();
    ITask task = mock(ITask.class);
    when(task.getName()).thenReturn("TestTask");
    when(task.getTaskDetails()).thenReturn(node);
    return task;
  }

  @Before
  public void setup() {
    taskExecutor = spy(app.injector().instanceOf(TaskExecutor.class));
    doAnswer(
            inv -> {
              Object[] objects = inv.getArguments();
              ITask task = (ITask) objects[0];
              // Create a new task info object.
              TaskInfo taskInfo = new TaskInfo(TaskType.BackupUniverse);
              taskInfo.setTaskDetails(RedactingService.filterSecretFields(task.getTaskDetails()));
              taskInfo.setOwner("test-owner");
              return taskInfo;
            })
        .when(taskExecutor)
        .createTaskInfo(any());
  }

  @Test
  public void testTaskSubmission() throws InterruptedException {
    ITask task = mockTaskCommon();
    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    TaskInfo taskInfo = waitForTask(taskUUID);
    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    assertEquals(0, subTaskInfos.size());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testTaskFailure() throws InterruptedException {
    ITask task = mockTaskCommon();
    doThrow(new RuntimeException("Error occurred in task")).when(task).run();
    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    UUID outTaskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    TaskInfo taskInfo = waitForTask(outTaskUUID);
    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    assertEquals(0, subTaskInfos.size());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    String errMsg = taskInfo.getTaskDetails().get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in task"));
  }

  @Test
  public void testSubTaskAsyncSuccess() throws InterruptedException {
    ITask task = mockTaskCommon();
    ITask subTask = mockTaskCommon();
    AtomicReference<UUID> taskUUIDRef = new AtomicReference<>();
    doAnswer(
            inv -> {
              RunnableTask runnable = taskExecutor.getRunnableTask(taskUUIDRef.get());
              // Invoke subTask from the parent task.
              SubTaskGroup subTasksGroup = taskExecutor.createSubTaskGroup("test");
              subTasksGroup.addSubTask(subTask);
              runnable.addSubTaskGroup(subTasksGroup);
              runnable.runSubTasks();
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    taskUUIDRef.set(taskRunner.getTaskUUID());
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    TaskInfo taskInfo = waitForTask(taskUUID);
    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTaskInfos.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    assertEquals(1, subTasksByPosition.size());
    verify(subTask, times(1)).run();
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    assertEquals(TaskInfo.State.Success, subTaskInfos.get(0).getTaskState());
  }

  @Test
  public void testSubTaskAsyncFailure() throws InterruptedException {
    ITask task = mockTaskCommon();
    ITask subTask = mockTaskCommon();
    AtomicReference<UUID> taskUUIDRef = new AtomicReference<>();
    doAnswer(
            inv -> {
              RunnableTask runnable = taskExecutor.getRunnableTask(taskUUIDRef.get());
              // Invoke subTask from the parent task.
              SubTaskGroup subTasksGroup = taskExecutor.createSubTaskGroup("test");
              subTasksGroup.addSubTask(subTask);
              runnable.addSubTaskGroup(subTasksGroup);
              runnable.runSubTasks();
              return null;
            })
        .when(task)
        .run();

    doThrow(new RuntimeException("Error occurred in subtask")).when(subTask).run();
    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    taskUUIDRef.set(taskRunner.getTaskUUID());
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    TaskInfo taskInfo = waitForTask(taskUUID);
    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTaskInfos.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    assertEquals(1, subTasksByPosition.size());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());

    String errMsg = taskInfo.getTaskDetails().get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Failed to execute task"));

    assertEquals(TaskInfo.State.Failure, subTaskInfos.get(0).getTaskState());
    errMsg = subTaskInfos.get(0).getTaskDetails().get("errorString").asText();
    assertTrue("Found " + errMsg, errMsg.contains("Error occurred in subtask"));
  }

  @Test
  public void testSubTaskNonAbortable() throws InterruptedException {
    ITask task = mockTaskCommon();
    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch.await();
              return null;
            })
        .when(task)
        .run();

    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    try {
      assertThrows(RuntimeException.class, () -> taskExecutor.abort(taskUUID));
    } finally {
      latch.countDown();
    }
    waitForTask(taskUUID);
  }

  @Test
  public void testSubTaskAbort() throws InterruptedException {
    ITask task = mockTaskCommon();
    ITask subTask1 = mockTaskCommon();
    ITask subTask2 = mockTaskCommon();
    AtomicReference<UUID> taskUUIDRef = new AtomicReference<>();
    doReturn(true).when(task).isAbortable();

    doAnswer(
            inv -> {
              RunnableTask runnable = taskExecutor.getRunnableTask(taskUUIDRef.get());
              // Invoke subTask from the parent task.
              SubTaskGroup subTasksGroup1 = taskExecutor.createSubTaskGroup("test1");
              subTasksGroup1.addSubTask(subTask1);
              runnable.addSubTaskGroup(subTasksGroup1);
              SubTaskGroup subTasksGroup2 = taskExecutor.createSubTaskGroup("test2");
              subTasksGroup2.addSubTask(subTask2);
              runnable.addSubTaskGroup(subTasksGroup2);
              runnable.runSubTasks();
              return null;
            })
        .when(task)
        .run();

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch1.countDown();
              latch2.await();
              return null;
            })
        .when(subTask1)
        .run();

    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    taskUUIDRef.set(taskRunner.getTaskUUID());
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    if (!latch1.await(200, TimeUnit.SECONDS)) {
      fail();
    }
    TaskInfo taskInfo = TaskInfo.getOrBadRequest(taskUUID);
    assertEquals(TaskInfo.State.Running, taskInfo.getTaskState());
    // Stop the task
    taskExecutor.abort(taskUUID);
    latch2.countDown();

    taskInfo = waitForTask(taskUUID);

    verify(subTask1, times(1)).run();
    verify(subTask2, times(0)).run();

    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTaskInfos.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    assertEquals(2, subTasksByPosition.size());
    assertEquals(TaskInfo.State.Aborted, taskInfo.getTaskState());
    assertEquals(TaskInfo.State.Success, subTaskInfos.get(0).getTaskState());
    assertEquals(TaskInfo.State.Aborted, subTaskInfos.get(1).getTaskState());
  }

  @Test
  public void testSubTaskAbortAtPosition() throws InterruptedException {
    ITask task = mockTaskCommon();
    ITask subTask1 = mockTaskCommon();
    ITask subTask2 = mockTaskCommon();
    AtomicReference<UUID> taskUUIDRef = new AtomicReference<>();
    doAnswer(
            inv -> {
              RunnableTask runnable = taskExecutor.getRunnableTask(taskUUIDRef.get());
              // Invoke subTask from the parent task.
              SubTaskGroup subTasksGroup1 = taskExecutor.createSubTaskGroup("test1");
              subTasksGroup1.addSubTask(subTask1);
              runnable.addSubTaskGroup(subTasksGroup1);
              SubTaskGroup subTasksGroup2 = taskExecutor.createSubTaskGroup("test2");
              subTasksGroup2.addSubTask(subTask2);
              runnable.addSubTaskGroup(subTasksGroup2);
              runnable.runSubTasks();
              return null;
            })
        .when(task)
        .run();

    AtomicInteger test = new AtomicInteger(0);
    TaskExecutor.RunnableTask taskRunner = taskExecutor.createRunnableTask(task);
    taskUUIDRef.set(taskRunner.getTaskUUID());
    taskRunner.setTaskExecutionListener(
        new TaskExecutionListener() {
          @Override
          public void beforeTask(TaskInfo tf) {
            test.incrementAndGet();
            if (tf.getPosition() == 1) {
              throw new CancellationException("cancelled");
            }
          }

          @Override
          public void afterTask(TaskInfo taskInfo, Throwable t) {}
        });
    UUID taskUUID = taskExecutor.submit(taskRunner, Executors.newFixedThreadPool(1));
    TaskInfo taskInfo = waitForTask(taskUUID);
    // 1 parent task + 2 subtasks.
    assertEquals(3, test.get());

    verify(subTask1, times(1)).run();
    verify(subTask2, times(0)).run();

    List<TaskInfo> subTaskInfos = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTaskInfos.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    assertEquals(2, subTasksByPosition.size());
    assertEquals(TaskInfo.State.Aborted, taskInfo.getTaskState());
    assertEquals(TaskInfo.State.Success, subTaskInfos.get(0).getTaskState());
    assertEquals(TaskInfo.State.Aborted, subTaskInfos.get(1).getTaskState());
  }
}
