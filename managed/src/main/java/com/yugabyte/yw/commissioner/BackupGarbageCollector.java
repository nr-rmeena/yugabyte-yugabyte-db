package com.yugabyte.yw.commissioner;

import akka.actor.ActorSystem;
import com.amazonaws.SDKGlobalConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.AWSUtil;
import com.yugabyte.yw.common.AZUtil;
import com.yugabyte.yw.common.GCPUtil;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TableManagerYb;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.customer.config.CustomerConfigService;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerConfig;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Backup.BackupState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import play.libs.Json;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

@Singleton
@Slf4j
public class BackupGarbageCollector {

  private final ActorSystem actorSystem;

  private final ExecutionContext executionContext;

  private final int YB_SET_BACKUP_GARBAGE_COLLECTOR_INTERVAL = 15;

  private final TableManagerYb tableManagerYb;

  private final CustomerConfigService customerConfigService;

  private static final String AZ = Util.AZ;
  private static final String GCS = Util.GCS;
  private static final String S3 = Util.S3;
  private static final String NFS = Util.NFS;

  @Inject
  public BackupGarbageCollector(
      ExecutionContext executionContext,
      ActorSystem actorSystem,
      CustomerConfigService customerConfigService,
      TableManagerYb tableManagerYb) {
    this.actorSystem = actorSystem;
    this.executionContext = executionContext;
    this.customerConfigService = customerConfigService;
    this.tableManagerYb = tableManagerYb;
  }

  public void start() {
    this.actorSystem
        .scheduler()
        .schedule(
            Duration.create(0, TimeUnit.MINUTES),
            Duration.create(YB_SET_BACKUP_GARBAGE_COLLECTOR_INTERVAL, TimeUnit.MINUTES),
            this::scheduleRunner,
            this.executionContext);
  }

  @VisibleForTesting
  void scheduleRunner() {
    log.info("Running Backup Garbage Collector");
    try {
      Customer.getAll()
          .forEach(
              (customer) -> {
                List<Backup> backupList = Backup.findAllBackupsQueuedForDeletion(customer.uuid);
                if (backupList != null) {
                  backupList.forEach((backup) -> deleteBackup(customer.uuid, backup.backupUUID));
                }
              });
    } catch (Exception e) {
      log.error("Error running backup garbage collector", e);
    }
  }

  public synchronized void deleteBackup(UUID customerUUID, UUID backupUUID) {
    Backup backup = Backup.maybeGet(customerUUID, backupUUID).orElse(null);
    // Backup is already deleted.
    if (backup == null || backup.state == BackupState.Deleted) {
      if (backup != null) {
        backup.delete();
      }
      return;
    }
    try {
      // Disable cert checking while connecting with s3
      // Enabling it can potentially fail when s3 compatible storages like
      // Dell ECS are provided and custom certs are needed to connect
      // Reference: https://yugabyte.atlassian.net/browse/PLAT-2497
      System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");

      UUID storageConfigUUID = backup.getBackupInfo().storageConfigUUID;
      CustomerConfig customerConfig =
          customerConfigService.getOrBadRequest(backup.customerUUID, storageConfigUUID);
      if (isCredentialUsable(customerConfig.data, customerConfig.name)) {
        List<String> backupLocations = null;
        log.info("Backup {} deletion started", backupUUID);
        backup.transitionState(BackupState.DeleteInProgress);
        try {
          switch (customerConfig.name) {
            case S3:
              backupLocations = getBackupLocations(backup);
              AWSUtil.deleteKeyIfExists(customerConfig.data, backupLocations.get(0));
              AWSUtil.deleteStorage(customerConfig.data, backupLocations);
              backup.delete();
              break;
            case GCS:
              backupLocations = getBackupLocations(backup);
              GCPUtil.deleteKeyIfExists(customerConfig.data, backupLocations.get(0));
              GCPUtil.deleteStorage(customerConfig.data, backupLocations);
              backup.delete();
              break;
            case AZ:
              backupLocations = getBackupLocations(backup);
              AZUtil.deleteKeyIfExists(customerConfig.data, backupLocations.get(0));
              AZUtil.deleteStorage(customerConfig.data, backupLocations);
              backup.delete();
              break;
            case NFS:
              if (isUniversePresent(backup)) {
                BackupTableParams backupParams = backup.getBackupInfo();
                List<BackupTableParams> backupList =
                    backupParams.backupList == null
                        ? ImmutableList.of(backupParams)
                        : backupParams.backupList;
                if (deleteNFSBackup(backupList)) {
                  backup.delete();
                  log.info("Backup {} is successfully deleted", backupUUID);
                } else {
                  backup.transitionState(BackupState.FailedToDelete);
                }
              } else {
                backup.delete();
                log.info("NFS Backup {} is deleted as universe is not present", backup.backupUUID);
              }
              break;
            default:
              backup.transitionState(Backup.BackupState.FailedToDelete);
              log.error(
                  "Backup {} deletion failed due to invalid Config type {} provided",
                  backup.backupUUID,
                  customerConfig.name);
          }
        } catch (Exception e) {
          log.error(" Error in deleting backup " + backup.backupUUID.toString(), e);
          backup.transitionState(Backup.BackupState.FailedToDelete);
        }
      } else {
        log.error(
            "Error while deleting backup {} due to invalid storage config {} : {}",
            backup.backupUUID,
            storageConfigUUID);
        backup.transitionState(BackupState.FailedToDelete);
      }
    } catch (Exception e) {
      log.error("Error while deleting backup " + backup.backupUUID, e);
      backup.transitionState(BackupState.FailedToDelete);
    } finally {
      // Re-enable cert checking as it applies globally
      System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "false");
    }
  }

  private Boolean isUniversePresent(Backup backup) {
    Optional<Universe> universe = Universe.maybeGet(backup.getBackupInfo().universeUUID);
    return universe.isPresent();
  }

  private boolean deleteNFSBackup(List<BackupTableParams> backupList) {
    boolean success = true;
    for (BackupTableParams childBackupParams : backupList) {
      if (!deleteChildNFSBackups(childBackupParams)) {
        success = false;
      }
    }
    return success;
  }

  private boolean deleteChildNFSBackups(BackupTableParams backupTableParams) {
    ShellResponse response = tableManagerYb.deleteBackup(backupTableParams);
    JsonNode jsonNode = null;
    try {
      jsonNode = Json.parse(response.message);
    } catch (Exception e) {
      log.error(
          "Delete Backup failed for {}. Response code={}, Output={}.",
          backupTableParams.storageLocation,
          response.code,
          response.message);
      return false;
    }
    if (response.code != 0 || jsonNode.has("error")) {
      log.error(
          "Delete Backup failed for {}. Response code={}, hasError={}.",
          backupTableParams.storageLocation,
          response.code,
          jsonNode.has("error"));
      return false;
    } else {
      log.info("NFS Backup deleted successfully STDOUT: " + response.message);
      return true;
    }
  }

  private static List<String> getBackupLocations(Backup backup) {
    BackupTableParams backupParams = backup.getBackupInfo();
    List<String> backupLocations = new ArrayList<>();
    if (backupParams.backupList != null) {
      for (BackupTableParams params : backupParams.backupList) {
        backupLocations.add(params.storageLocation);
      }
    } else {
      backupLocations.add(backupParams.storageLocation);
    }
    return backupLocations;
  }

  private Boolean isCredentialUsable(JsonNode credentials, String configName) {
    Boolean isValid = false;
    switch (configName) {
      case S3:
        isValid = AWSUtil.canCredentialListObjects(credentials);
        break;
      case GCS:
        isValid = GCPUtil.canCredentialListObjects(credentials);
        break;
      case AZ:
        isValid = AZUtil.canCredentialListObjects(credentials);
        break;
      case NFS:
        isValid = true;
        break;
      default:
        log.error("Invalid Config type {} provided", configName);
        isValid = false;
    }
    return isValid;
  }
}
