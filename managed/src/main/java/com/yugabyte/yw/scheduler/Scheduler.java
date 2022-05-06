/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1
 * .0.0.txt
 */

package com.yugabyte.yw.scheduler;

import static com.cronutils.model.CronType.UNIX;

import akka.actor.ActorSystem;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.BackupUniverse;
import com.yugabyte.yw.commissioner.tasks.CreateBackup;
import com.yugabyte.yw.commissioner.tasks.MultiTableBackup;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteBackupYb;
import com.yugabyte.yw.commissioner.tasks.subtasks.RunExternalScript;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.HighAvailabilityConfig;
import com.yugabyte.yw.models.Schedule;
import com.yugabyte.yw.models.ScheduleTask;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import play.libs.Json;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

@Singleton
@Slf4j
public class Scheduler {

  private static final int YB_SCHEDULER_INTERVAL = 2;
  private static final int MIN_TO_SEC = 60;

  private final ActorSystem actorSystem;
  private final ExecutionContext executionContext;

  private final AtomicBoolean running = new AtomicBoolean(false);

  private final Commissioner commissioner;

  @Inject
  Scheduler(ActorSystem actorSystem, ExecutionContext executionContext, Commissioner commissioner) {
    this.actorSystem = actorSystem;
    this.executionContext = executionContext;
    this.commissioner = commissioner;
  }

  public void start() {
    log.info("Starting scheduling service");
    this.actorSystem
        .scheduler()
        .schedule(
            Duration.create(0, TimeUnit.MINUTES), // initialDelay
            Duration.create(YB_SCHEDULER_INTERVAL, TimeUnit.MINUTES), // interval
            this::scheduleRunner,
            this.executionContext);
  }

  /** Resets every schedule's running state to false in case of platform restart. */
  public void resetRunningStatus() {
    Schedule.getAll()
        .forEach(
            (schedule) -> {
              if (schedule.getRunningState()) {
                schedule.setRunningState(false);
                log.debug("Updated scheduler {} running state to false", schedule.scheduleUUID);
              }
            });
  }

  /** Iterates through all the schedule entries and runs the tasks that are due to be scheduled. */
  @VisibleForTesting
  void scheduleRunner() {
    if (!running.compareAndSet(false, true)) {
      log.info("Previous run of scheduler is still underway");
      return;
    }

    try {
      if (HighAvailabilityConfig.isFollower()) {
        log.debug("Skipping scheduler for follower platform");
        return;
      }

      log.info("Running scheduler");
      for (Schedule schedule : Schedule.getAllActive()) {
        Date currentTime = new Date();
        long frequency = schedule.getFrequency();
        String cronExpression = schedule.getCronExpression();
        if (cronExpression == null && frequency == 0) {
          log.error(
              "Scheduled task does not have a recurrence specified {}", schedule.getScheduleUUID());
          continue;
        }
        try {
          schedule.setRunningState(true);
          TaskType taskType = schedule.getTaskType();
          // TODO: Come back and maybe address if using relations between schedule and
          //  schedule_task is a better approach.
          ScheduleTask lastTask = ScheduleTask.getLastTask(schedule.getScheduleUUID());
          Date lastScheduledTime = null;
          Date lastCompletedTime = null;
          if (lastTask != null) {
            lastScheduledTime = lastTask.getScheduledTime();
            lastCompletedTime = lastTask.getCompletedTime();
          }
          boolean shouldRunTask = false;
          boolean alreadyRunning = false;
          long diff;

          // Check if task needs to be scheduled again.
          if (lastScheduledTime != null) {
            diff = Math.abs(currentTime.getTime() - lastScheduledTime.getTime());
            if (lastCompletedTime == null) {
              alreadyRunning = true;
            }
          } else {
            diff = Long.MAX_VALUE;
          }
          // If frequency if specified, check if the task needs to be scheduled.
          // The check sees the difference between the last scheduled task and the current
          // time. If the diff is greater than the frequency, means we need to run the task
          // again.
          if (frequency != 0L && diff > frequency) {
            shouldRunTask = true;
          }
          // In the case frequency is not defined and we have a cron expression, we compute
          // solely in accordance to the cron execution time. If the execution time is within the
          // scheduler interval, we run the task.
          else if (cronExpression != null) {
            CronParser unixCronParser =
                new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));
            Cron parsedUnixCronExpression = unixCronParser.parse(cronExpression);
            Instant now = Instant.now();
            // LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
            ZonedDateTime utcNow = now.atZone(ZoneId.of("UTC"));
            ExecutionTime executionTime = ExecutionTime.forCron(parsedUnixCronExpression);
            long timeFromLastExecution =
                executionTime.timeFromLastExecution(utcNow).get().getSeconds();
            if (timeFromLastExecution < YB_SCHEDULER_INTERVAL * MIN_TO_SEC) {
              // In case the last task was completed, or the last task was never even scheduled,
              // we run the task. If the task was scheduled, but didn't complete, we skip this
              // iteration completely.
              shouldRunTask = true;
              if (lastScheduledTime != null && lastCompletedTime == null) {
                log.warn(
                    "Previous scheduled task still running, skipping this iteration's task. "
                        + "Will try again next at {}.",
                    executionTime.nextExecution(utcNow).get());
              }
            }
          }
          if (shouldRunTask) {
            if (taskType == TaskType.BackupUniverse) {
              this.runBackupTask(schedule, alreadyRunning);
            }
            if (taskType == TaskType.MultiTableBackup) {
              this.runMultiTableBackupsTask(schedule, alreadyRunning);
            }
            if (taskType == TaskType.ExternalScript && !alreadyRunning) {
              this.runExternalScriptTask(schedule);
            }
            if (taskType == TaskType.CreateBackup) {
              this.runCreateBackupTask(schedule, alreadyRunning);
            }
          }
        } catch (Exception e) {
          log.error("Error runnning schedule {} ", schedule.scheduleUUID, e);
        } finally {
          schedule.setRunningState(false);
        }
      }
      Map<Customer, List<Backup>> expiredBackups = Backup.getExpiredBackups();
      expiredBackups.forEach(
          (customer, backups) -> {
            deleteExpiredBackupsForCustomer(customer, backups);
          });
    } catch (Exception e) {
      log.error("Error Running scheduler thread", e);
    } finally {
      running.set(false);
    }
  }

  private void deleteExpiredBackupsForCustomer(Customer customer, List<Backup> expiredBackups) {
    Map<UUID, List<Backup>> expiredBackupsPerSchedule = new HashMap<>();
    List<Backup> backupsToDelete = new ArrayList<>();
    expiredBackups.forEach(
        backup -> {
          UUID scheduleUUID = backup.getScheduleUUID();
          if (scheduleUUID == null) {
            backupsToDelete.add(backup);
          } else {
            if (!expiredBackupsPerSchedule.containsKey(scheduleUUID)) {
              expiredBackupsPerSchedule.put(scheduleUUID, new ArrayList<>());
            }
            expiredBackupsPerSchedule.get(scheduleUUID).add(backup);
          }
        });
    for (UUID scheduleUUID : expiredBackupsPerSchedule.keySet()) {
      backupsToDelete.addAll(
          getBackupsToDeleteForSchedule(
              customer.getUuid(), scheduleUUID, expiredBackupsPerSchedule.get(scheduleUUID)));
    }

    for (Backup backup : backupsToDelete) {
      this.runDeleteBackupTask(customer, backup);
    }
  }

  private List<Backup> getBackupsToDeleteForSchedule(
      UUID customerUUID, UUID scheduleUUID, List<Backup> expiredBackups) {
    List<Backup> backupsToDelete = new ArrayList<Backup>();
    int minNumBackupsToRetain = Util.MIN_NUM_BACKUPS_TO_RETAIN,
        totalBackupsCount = Backup.fetchAllBackupsByScheduleUUID(customerUUID, scheduleUUID).size();
    Schedule schedule = Schedule.getOrBadRequest(scheduleUUID);
    if (schedule.getTaskParams().has("minNumBackupsToRetain")) {
      minNumBackupsToRetain = schedule.getTaskParams().get("minNumBackupsToRetain").intValue();
    }

    int numBackupsToDelete =
        Math.min(expiredBackups.size(), Math.max(0, totalBackupsCount - minNumBackupsToRetain));
    Collections.sort(
        expiredBackups,
        new Comparator<Backup>() {
          @Override
          public int compare(Backup b1, Backup b2) {
            return b1.getCreateTime().compareTo(b2.getCreateTime());
          }
        });
    for (int i = 0; i < Math.min(numBackupsToDelete, expiredBackups.size()); i++) {
      backupsToDelete.add(expiredBackups.get(i));
    }
    return backupsToDelete;
  }

  private void runBackupTask(Schedule schedule, boolean alreadyRunning) {
    BackupUniverse backupUniverse = AbstractTaskBase.createTask(BackupUniverse.class);
    backupUniverse.runScheduledBackup(schedule, commissioner, alreadyRunning);
  }

  private void runMultiTableBackupsTask(Schedule schedule, boolean alreadyRunning) {
    MultiTableBackup multiTableBackup = AbstractTaskBase.createTask(MultiTableBackup.class);
    multiTableBackup.runScheduledBackup(schedule, commissioner, alreadyRunning);
  }

  private void runCreateBackupTask(Schedule schedule, boolean alreadyRunning) {
    CreateBackup createBackup = AbstractTaskBase.createTask(CreateBackup.class);
    createBackup.runScheduledBackup(schedule, commissioner, alreadyRunning);
  }

  private void runDeleteBackupTask(Customer customer, Backup backup) {
    if (Backup.IN_PROGRESS_STATES.contains(backup.state)) {
      log.warn("Cannot delete backup {} since it is in a progress state");
      return;
    }
    DeleteBackupYb.Params taskParams = new DeleteBackupYb.Params();
    taskParams.customerUUID = customer.getUuid();
    taskParams.backupUUID = backup.backupUUID;
    UUID taskUUID = commissioner.submit(TaskType.DeleteBackupYb, taskParams);
    log.info("Submitted task to delete backup {}, task uuid = {}.", backup.backupUUID, taskUUID);
    CustomerTask.create(
        customer,
        backup.backupUUID,
        taskUUID,
        CustomerTask.TargetType.Backup,
        CustomerTask.TaskType.Delete,
        "Backup");
  }

  private void runExternalScriptTask(Schedule schedule) {
    JsonNode params = schedule.getTaskParams();
    RunExternalScript.Params taskParams = Json.fromJson(params, RunExternalScript.Params.class);
    Customer customer = Customer.getOrBadRequest(taskParams.customerUUID);
    Universe universe;
    try {
      universe = Universe.getOrBadRequest(taskParams.universeUUID);
    } catch (Exception e) {
      schedule.stopSchedule();
      log.info(
          "External script scheduler is stopped for the universe {} as universe was deleted.",
          taskParams.universeUUID);
      return;
    }
    UUID taskUUID = commissioner.submit(TaskType.ExternalScript, taskParams);
    ScheduleTask.create(taskUUID, schedule.getScheduleUUID());
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.ExternalScript,
        universe.name);
    log.info(
        "Submitted external script task with task uuid = {} for universe {}.",
        taskUUID,
        universe.universeUUID);
  }
}
