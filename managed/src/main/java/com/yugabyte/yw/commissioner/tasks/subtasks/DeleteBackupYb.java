package com.yugabyte.yw.commissioner.tasks.subtasks;

import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.common.BackupUtil;
import com.yugabyte.yw.forms.AbstractTaskParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Backup.BackupState;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteBackupYb extends AbstractTaskBase {
  @Inject
  public DeleteBackupYb(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Inject private BackupUtil backupUtil;

  public static class Params extends AbstractTaskParams {
    public UUID customerUUID;
    public UUID backupUUID;
  }

  public Params params() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    Backup backup = Backup.getOrBadRequest(params().customerUUID, params().backupUUID);
    if (Backup.IN_PROGRESS_STATES.contains(backup.getState())) {
      log.error("Cannot delete backup that are in {} state", backup.getState());
      return;
    }
    boolean updateState = true;
    try {
      backupUtil.validateBackupStorageConfig(backup);
      Set<Backup> backupsToDelete = new HashSet<>();
      if (backup.isParentBackup()) {
        if (BackupUtil.checkInProgressIncrementalBackup(backup)) {
          updateState = false;
          throw new RuntimeException(
              "Cannot delete backup "
                  + backup.getBackupUUID()
                  + " as a incremental/full backup is in progress.");
        }
        backupsToDelete.addAll(
            Backup.fetchAllBackupsByBaseBackupUUID(
                backup.getCustomerUUID(), backup.getBackupUUID()));
      }
      backupsToDelete.add(backup);
      backupsToDelete.forEach(
          (backupToBeDeleted) -> backupToBeDeleted.transitionState(BackupState.QueuedForDeletion));
    } catch (Exception e) {
      log.error("Errored out with: " + e);
      if (updateState) {
        backup.transitionState(BackupState.FailedToDelete);
      }
      throw new RuntimeException(e.getMessage());
    }
  }
}
