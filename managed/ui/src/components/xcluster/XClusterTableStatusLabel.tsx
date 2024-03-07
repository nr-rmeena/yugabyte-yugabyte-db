import { useQuery } from 'react-query';
import clsx from 'clsx';
import { Typography } from '@material-ui/core';

import { AlertName, XClusterTableStatus } from './constants';
import { assertUnreachableCase } from '../../utils/errorHandlingUtils';
import { getAlertConfigurations } from '../../actions/universe';
import { YBLoadingCircleIcon } from '../common/indicators';
import { alertConfigQueryKey } from '../../redesign/helpers/api';

import { usePillStyles } from '../../redesign/styles/styles';

interface XClusterTableStatusProps {
  replicationLag: number | undefined;
  sourceUniverseUuid: string;
  status: XClusterTableStatus;
}

export const XClusterTableStatusLabel = ({
  replicationLag,
  sourceUniverseUuid,
  status
}: XClusterTableStatusProps) => {
  const classes = usePillStyles();
  const alertConfigFilter = {
    name: AlertName.REPLICATION_LAG,
    targetUuid: sourceUniverseUuid
  };
  const maxAcceptableLagQuery = useQuery(alertConfigQueryKey.list(alertConfigFilter), () =>
    getAlertConfigurations(alertConfigFilter)
  );

  switch (status) {
    case XClusterTableStatus.RUNNING: {
      if (maxAcceptableLagQuery.isLoading || maxAcceptableLagQuery.isIdle) {
        return <YBLoadingCircleIcon />;
      }

      const maxAcceptableLag = Math.min(
        ...maxAcceptableLagQuery.data.map(
          (alertConfig: any): number => alertConfig.thresholds.SEVERE.threshold
        )
      );
      return replicationLag === undefined || replicationLag > maxAcceptableLag ? (
        <Typography variant="body2" className={clsx(classes.pill, classes.warning)}>
          Warning
          <i className="fa fa-exclamation-triangle" />
        </Typography>
      ) : (
        <Typography variant="body2" className={clsx(classes.pill, classes.ready)}>
          Operational
          <i className="fa fa-check" />
        </Typography>
      );
    }
    case XClusterTableStatus.WARNING:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.warning)}>
          Warning
          <i className="fa fa-exclamation-triangle" />
        </Typography>
      );
    case XClusterTableStatus.FAILED:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.danger)}>
          Failed
          <i className="fa fa-exclamation-circle" />
        </Typography>
      );
    case XClusterTableStatus.ERROR:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.danger)}>
          Error
          <i className="fa fa-exclamation-circle" />
        </Typography>
      );
    case XClusterTableStatus.UPDATING:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.inProgress)}>
          In Progress
          <i className="fa fa-spinner fa-spin" />
        </Typography>
      );
    case XClusterTableStatus.VALIDATED:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.inProgress)}>
          Validated
          <i className="fa fa-spinner fa-spin" />
        </Typography>
      );
    case XClusterTableStatus.BOOTSTRAPPING:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.inProgress)}>
          Bootstrapping
          <i className="fa fa-spinner fa-spin" />
        </Typography>
      );
    case XClusterTableStatus.UNABLE_TO_FETCH:
      return (
        <Typography variant="body2" className={clsx(classes.pill, classes.warning)}>
          Unable To Fetch
          <i className="fa fa-exclamation-triangle" />
        </Typography>
      );
    default:
      return assertUnreachableCase(status);
  }
};
