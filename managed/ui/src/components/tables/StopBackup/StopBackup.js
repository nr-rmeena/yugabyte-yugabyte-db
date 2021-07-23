import React from 'react';
import { YBModal } from '../../common/forms/fields';

export const StopBackup = (props) => {
  const { tableInfo, visible, onHide, stopBackup, onSubmit, onError } = props;

  const confirmStopBackup = async () => {
    try {
      const response = await stopBackup(tableInfo.backupUUID);
      onSubmit(response.data);
    } catch (err) {
      if (onError) {
        onError();
      }
    }

    onHide();
  };
  return (
    <div className="universe-apps-modal">
      <YBModal
        title="Stop Backup"
        visible={visible}
        onHide={onHide}
        showCancelButton={true}
        cancelLabel="Cancel"
        onFormSubmit={confirmStopBackup}
      >
        Are you sure you want to stop this backup?
      </YBModal>
    </div>
  );
};
