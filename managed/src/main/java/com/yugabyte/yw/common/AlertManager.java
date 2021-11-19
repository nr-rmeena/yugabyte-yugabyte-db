/*
 * Copyright 2020 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common;

import static com.yugabyte.yw.models.helpers.CommonUtils.nowMinusWithoutMillis;
import static com.yugabyte.yw.models.helpers.CommonUtils.nowPlusWithoutMillis;

import com.google.common.annotations.VisibleForTesting;
import com.yugabyte.yw.common.alerts.AlertChannelEmailParams;
import com.yugabyte.yw.common.alerts.AlertChannelInterface;
import com.yugabyte.yw.common.alerts.AlertChannelManager;
import com.yugabyte.yw.common.alerts.AlertChannelService;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDestinationService;
import com.yugabyte.yw.common.alerts.AlertNotificationReport;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.alerts.AlertUtils;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.Alert.State;
import com.yugabyte.yw.models.AlertChannel;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertDestination;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.filters.AlertFilter;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class AlertManager {

  @VisibleForTesting static final int NOTIFICATION_REPEAT_AFTER_FAILURE_IN_SECS = 180;

  private final EmailHelper emailHelper;
  private final AlertConfigurationService alertConfigurationService;
  private final AlertChannelService alertChannelService;
  private final AlertDestinationService alertDestinationService;
  private final AlertChannelManager channelsManager;
  private final AlertService alertService;
  private final MetricService metricService;

  @Inject
  public AlertManager(
      EmailHelper emailHelper,
      AlertService alertService,
      AlertConfigurationService alertConfigurationService,
      AlertChannelService alertChannelService,
      AlertDestinationService alertDestinationService,
      AlertChannelManager channelsManager,
      MetricService metricService) {
    this.emailHelper = emailHelper;
    this.alertService = alertService;
    this.alertConfigurationService = alertConfigurationService;
    this.alertChannelService = alertChannelService;
    this.alertDestinationService = alertDestinationService;
    this.channelsManager = channelsManager;
    this.metricService = metricService;
  }

  private NotificationStrategy getNotificationStrategy(Alert alert) {
    String configurationUuid = alert.getLabelValue(KnownAlertLabels.CONFIGURATION_UUID);
    if (configurationUuid == null) {
      log.warn("Missing configuration UUID in alert {}", alert.getUuid());
      return new NotificationStrategy("Alert configuration UUID is missing in Alert");
    }
    AlertConfiguration configuration =
        alertConfigurationService.get(UUID.fromString(configurationUuid));
    if (configuration == null) {
      log.warn("Missing configuration {} for alert {}", configurationUuid, alert.getUuid());
      return new NotificationStrategy("Alert configuration is missing");
    }
    AlertDestination destination;
    boolean defaultDestination = false;
    if (configuration.getDestinationUUID() == null) {
      if (!configuration.isDefaultDestination()) {
        return new NotificationStrategy("No destination defined for alert");
      }
      log.trace("Using default destination {} for alert {}", configurationUuid, alert.getUuid());
      defaultDestination = true;
      destination = alertDestinationService.getDefaultDestination(configuration.getCustomerUUID());
    } else {
      destination =
          alertDestinationService.get(
              configuration.getCustomerUUID(), configuration.getDestinationUUID());
    }
    if (destination == null) {
      log.warn(
          "Missing destination {} for alert {}",
          configuration.getDestinationUUID(),
          alert.getUuid());
    }

    return new NotificationStrategy(destination, defaultDestination);
  }

  @VisibleForTesting
  boolean sendNotificationForState(Alert alert, State state, AlertNotificationReport report) {
    SendNotificationStatus result = SendNotificationStatus.FAILED_TO_RESCHEDULE;
    try {
      result = sendNotification(alert, state, report).getStatus();
      if (result == SendNotificationStatus.FAILED_NO_RESCHEDULE) {
        // Failed, no reschedule is required.
        alert.setNextNotificationTime(null);
        alert.save();
        report.failAttempt();
        return false;
      }

      alert.setNotificationAttemptTime(new Date());
      if (result == SendNotificationStatus.FAILED_TO_RESCHEDULE) {
        alert.setNotificationsFailed(alert.getNotificationsFailed() + 1);

        Date switchStateTime = getSwitchStateTime(alert);
        if ((switchStateTime != null)
            && switchStateTime.before(nowMinusWithoutMillis(1, ChronoUnit.DAYS))) {
          log.trace("Unable to send notification for alert {}. Stop trying.", alert.getUuid());
          alert.setNextNotificationTime(null);
          alert.save();
          return false;
        }

        // For now using fixed delay before the notification repeat. Later the behavior
        // can be adjusted using an amount of failed attempts (using progressive value).
        alert.setNextNotificationTime(
            nowPlusWithoutMillis(NOTIFICATION_REPEAT_AFTER_FAILURE_IN_SECS, ChronoUnit.SECONDS));
        log.trace(
            "Next time to send notification for alert {} is {}",
            alert.getUuid(),
            alert.getNextNotificationTime());

        report.failAttempt();
      } else {
        // TODO: No repeats for now. Later should be updated along with the according
        // parameter introduced in AlertDestination.
        alert.setNextNotificationTime(null);
        alert.setNotificationsFailed(0);
        alert.setNotifiedState(state);
        log.trace("Notification sent for alert {}", alert.getUuid());
      }
      alert.save();

    } catch (Exception e) {
      report.failAttempt();
      log.error("Error while sending notification for alert {}", alert.getUuid(), e);
    }
    return !result.isFailure();
  }

  private Date getSwitchStateTime(Alert alert) {
    switch (alert.getState()) {
      case ACTIVE:
        return alert.getCreateTime();
      case ACKNOWLEDGED:
        return alert.getAcknowledgedTime();
      case RESOLVED:
        return alert.getResolvedTime();
    }
    return null;
  }

  public void sendNotifications() {
    AlertFilter filter =
        AlertFilter.builder()
            .state(Alert.State.ACTIVE, Alert.State.RESOLVED)
            .notificationPending(true)
            .build();
    List<Alert> toNotify = alertService.list(filter);
    if (toNotify.size() == 0) {
      return;
    }

    log.debug("Sending notifications, {} alerts to proceed.", toNotify.size());
    AlertNotificationReport report = new AlertNotificationReport();
    for (Alert alert : toNotify) {
      try {
        if (alert.getNotifiedState() == null) {
          report.raiseAttempt();
          if (!sendNotificationForState(alert, State.ACTIVE, report)) {
            continue;
          }
        }

        if ((alert.getNotifiedState().ordinal() < State.RESOLVED.ordinal())
            && (alert.getState() == State.RESOLVED)) {
          report.resolveAttempt();
          sendNotificationForState(alert, State.RESOLVED, report);
        }

      } catch (Exception e) {
        report.failAttempt();
        log.error("Error while sending notification for alert {}", alert.getUuid(), e);
      }
    }
    if (!report.isEmpty()) {
      log.info("{}", report);
    }
  }

  public SendNotificationResult sendNotification(Alert alert) {
    return sendNotification(alert, null, new AlertNotificationReport());
  }

  private SendNotificationResult sendNotification(
      Alert alert, State stateToNotify, AlertNotificationReport report) {
    Customer customer = Customer.get(alert.getCustomerUUID());

    boolean atLeastOneSucceeded = false;
    NotificationStrategy strategy = getNotificationStrategy(alert);

    if (!strategy.isShouldSend()) {
      log.debug("Skipping notification for alert {}", alert.getUuid());
      return new SendNotificationResult(SendNotificationStatus.SKIPPED, strategy.getMessage());
    }

    if (strategy.getDestination() == null) {
      if (strategy.isDefaultDestinationUsed()) {
        log.warn(
            "Unable to notify about alert {}, there is no default destination specified.",
            alert.getUuid());
        metricService.setStatusMetric(
            MetricService.buildMetricTemplate(PlatformMetrics.ALERT_MANAGER_STATUS, customer),
            "Unable to notify about alert(s), there is no default destination specified.");
        return new SendNotificationResult(
            SendNotificationStatus.FAILED_TO_RESCHEDULE, "No default destination configured");
      } else {
        log.error(
            "Unable to notify about alert {}, destination is missing from DB.", alert.getUuid());
        return new SendNotificationResult(
            SendNotificationStatus.FAILED_NO_RESCHEDULE, "Alert destination is missing");
      }
    }

    List<AlertChannel> channels = new ArrayList<>(strategy.getDestination().getChannelsList());

    if ((channels.size() == 1)
        && ("Email".equals(AlertUtils.getJsonTypeName(channels.get(0).getParams())))
        && ((AlertChannelEmailParams) channels.get(0).getParams()).isDefaultRecipients()
        && CollectionUtils.isEmpty(emailHelper.getDestinations(customer.getUuid()))) {

      metricService.setStatusMetric(
          MetricService.buildMetricTemplate(PlatformMetrics.ALERT_MANAGER_STATUS, customer),
          "Unable to notify about alert(s) using default destination, "
              + "there are no recipients configured in the customer's profile.");
      return new SendNotificationResult(
          SendNotificationStatus.FAILED_TO_RESCHEDULE,
          "No recipients configured in Health settings");
    }

    metricService.setOkStatusMetric(
        MetricService.buildMetricTemplate(PlatformMetrics.ALERT_MANAGER_STATUS, customer));

    // Not going to save the alert, only to use with another state for the
    // notification.
    Alert tempAlert = alert;
    if (stateToNotify != null) {
      tempAlert = alertService.get(alert.getUuid());
      if (tempAlert == null) {
        // The alert was not found. Most probably it is removed during the processing.
        return new SendNotificationResult(
            SendNotificationStatus.FAILED_NO_RESCHEDULE, "Alert not found in DB");
      }
      tempAlert.setState(stateToNotify);
    }

    for (AlertChannel channel : channels) {
      try {
        alertChannelService.validate(channel);
      } catch (PlatformServiceException e) {

        if (report.failuresByChannel(channel.getUuid()) == 0) {
          log.warn(String.format("Channel %s skipped: %s", channel.getUuid(), e.getMessage()), e);
        }
        handleChannelSendError(channel, report, "Misconfigured alert channel: " + e.getMessage());
        continue;
      }

      try {
        AlertChannelInterface handler =
            channelsManager.get(AlertUtils.getJsonTypeName(channel.getParams()));
        handler.sendNotification(customer, tempAlert, channel);
        atLeastOneSucceeded = true;
        setOkChannelStatusMetric(PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS, channel);
      } catch (PlatformServiceException e) {
        if (report.failuresByChannel(channel.getUuid()) == 0) {
          log.error(e.getMessage(), e);
        }
        handleChannelSendError(channel, report, e.getMessage());
      } catch (Exception e) {
        if (report.failuresByChannel(channel.getUuid()) == 0) {
          log.error(e.getMessage(), e);
        }
        handleChannelSendError(channel, report, "Error sending notification: " + e.getMessage());
      }
    }

    return atLeastOneSucceeded
        ? new SendNotificationResult(SendNotificationStatus.SUCCEEDED, "Alert sent successfully")
        : new SendNotificationResult(
            SendNotificationStatus.FAILED_TO_RESCHEDULE, "All notification channels failed");
  }

  private void handleChannelSendError(
      AlertChannel channel, AlertNotificationReport report, String alertMessage) {
    report.failChannel(channel.getUuid());
    setChannelStatusMetric(PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS, channel, alertMessage);
  }

  @VisibleForTesting
  void setOkChannelStatusMetric(PlatformMetrics metric, AlertChannel channel) {
    setChannelStatusMetric(metric, channel, StringUtils.EMPTY);
  }

  @VisibleForTesting
  void setChannelStatusMetric(PlatformMetrics metric, AlertChannel channel, String message) {
    boolean isSuccess = StringUtils.isEmpty(message);
    Metric statusMetric = buildMetricTemplate(metric, channel).setValue(isSuccess ? 1.0 : 0.0);
    if (!isSuccess) {
      statusMetric.setLabel(KnownAlertLabels.ERROR_MESSAGE, message);
    }
    metricService.cleanAndSave(Collections.singletonList(statusMetric));
  }

  private Metric buildMetricTemplate(PlatformMetrics metric, AlertChannel channel) {
    return new Metric()
        .setExpireTime(
            nowPlusWithoutMillis(MetricService.DEFAULT_METRIC_EXPIRY_SEC, ChronoUnit.SECONDS))
        .setCustomerUUID(channel.getCustomerUUID())
        .setType(Metric.Type.GAUGE)
        .setName(metric.getMetricName())
        .setSourceUuid(channel.getUuid())
        .setLabels(MetricLabelsBuilder.create().appendSource(channel).getMetricLabels());
  }

  @Value
  @AllArgsConstructor
  private static class NotificationStrategy {
    boolean shouldSend;
    AlertDestination destination;
    boolean defaultDestinationUsed;
    String message;

    NotificationStrategy(String message) {
      this(false, null, false, message);
    }

    NotificationStrategy(AlertDestination destination, boolean isDefault) {
      this(true, destination, isDefault, StringUtils.EMPTY);
    }
  }

  @Value
  @AllArgsConstructor
  public static class SendNotificationResult {
    SendNotificationStatus status;
    String message;
  }

  public enum SendNotificationStatus {
    SUCCEEDED(false),
    SKIPPED(false),
    FAILED_TO_RESCHEDULE(true),
    FAILED_NO_RESCHEDULE(true);

    private final boolean isFailure;

    SendNotificationStatus(boolean isFailure) {
      this.isFailure = isFailure;
    }

    public boolean isFailure() {
      return isFailure;
    }
  }
}
