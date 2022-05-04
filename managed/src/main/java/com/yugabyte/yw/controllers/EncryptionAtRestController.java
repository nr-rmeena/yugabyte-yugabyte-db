/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.params.KMSConfigTaskParams;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.kms.util.EncryptionAtRestUtil;
import com.yugabyte.yw.common.kms.util.KeyProvider;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Result;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public class EncryptionAtRestController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(EncryptionAtRestController.class);

  @Inject EncryptionAtRestManager keyManager;

  @Inject Commissioner commissioner;

  public Result createKMSConfig(UUID customerUUID, String keyProvider) {
    LOG.info(
        String.format(
            "Creating KMS configuration for customer %s with %s",
            customerUUID.toString(), keyProvider));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      TaskType taskType = TaskType.CreateKMSConfig;
      ObjectNode formData = (ObjectNode) request().body().asJson();
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = Enum.valueOf(KeyProvider.class, keyProvider);
      taskParams.providerConfig = formData;
      taskParams.customerUUID = customerUUID;
      taskParams.kmsConfigName = formData.get("name").asText();
      formData.remove("name");
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted create KMS config for {}, task uuid = {}.", customerUUID, taskUUID);
      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Create,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);

      auditService().createAuditEntry(ctx(), request(), formData);
      return new YWResults.YWTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  public Result getKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(String.format("Retrieving KMS configuration %s", configUUID.toString()));
    KmsConfig config = KmsConfig.get(configUUID);
    ObjectNode kmsConfig =
        keyManager.getServiceInstance(config.keyProvider.name()).getAuthConfig(configUUID);
    if (kmsConfig == null) {
      throw new YWServiceException(
          BAD_REQUEST,
          String.format("No KMS configuration found for config %s", configUUID.toString()));
    }
    return ApiResponse.success(kmsConfig);
  }

  public Result listKMSConfigs(UUID customerUUID) {
    LOG.info(String.format("Listing KMS configurations for customer %s", customerUUID.toString()));
    List<JsonNode> kmsConfigs =
        KmsConfig.listKMSConfigs(customerUUID)
            .stream()
            .map(
                configModel -> {
                  ObjectNode result = null;
                  ObjectNode credentials =
                      keyManager
                          .getServiceInstance(configModel.keyProvider.name())
                          .getAuthConfig(configModel.configUUID);
                  if (credentials != null) {
                    result = Json.newObject();
                    ObjectNode metadata = Json.newObject();
                    metadata.put("configUUID", configModel.configUUID.toString());
                    metadata.put("provider", configModel.keyProvider.name());
                    metadata.put(
                        "in_use", EncryptionAtRestUtil.configInUse(configModel.configUUID));
                    metadata.put(
                        "universeDetails",
                        EncryptionAtRestUtil.getUniverses(configModel.configUUID));
                    metadata.put("name", configModel.name);
                    result.put("credentials", CommonUtils.maskConfig(credentials));
                    result.put("metadata", metadata);
                  }
                  return result;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return ApiResponse.success(kmsConfigs);
  }

  public Result deleteKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(
        String.format(
            "Deleting KMS configuration %s for customer %s",
            configUUID.toString(), customerUUID.toString()));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      KmsConfig config = KmsConfig.get(configUUID);
      TaskType taskType = TaskType.DeleteKMSConfig;
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = config.keyProvider;
      taskParams.customerUUID = customerUUID;
      taskParams.configUUID = configUUID;
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted delete KMS config for {}, task uuid = {}.", customerUUID, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Delete,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);
      auditService().createAuditEntry(ctx(), request());
      return new YWResults.YWTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  public Result retrieveKey(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving universe key for universe %s",
            customerUUID.toString(), universeUUID.toString()));
    ObjectNode formData = (ObjectNode) request().body().asJson();
    byte[] keyRef = Base64.getDecoder().decode(formData.get("reference").asText());
    UUID configUUID = UUID.fromString(formData.get("configUUID").asText());
    byte[] recoveredKey = getRecoveredKeyOrBadRequest(universeUUID, configUUID, keyRef);
    ObjectNode result =
        Json.newObject()
            .put("reference", keyRef)
            .put("value", Base64.getEncoder().encodeToString(recoveredKey));
    auditService().createAuditEntry(ctx(), request(), formData);
    return ApiResponse.success(result);
  }

  public byte[] getRecoveredKeyOrBadRequest(UUID universeUUID, UUID configUUID, byte[] keyRef) {
    byte[] recoveredKey = keyManager.getUniverseKey(universeUUID, configUUID, keyRef);
    if (recoveredKey == null || recoveredKey.length == 0) {
      final String errMsg =
          String.format("No universe key found for universe %s", universeUUID.toString());
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }
    return recoveredKey;
  }

  public Result getKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref history for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    return ApiResponse.success(
        KmsHistory.getAllTargetKeyRefs(universeUUID, KmsHistoryId.TargetType.UNIVERSE_KEY)
            .stream()
            .map(
                history -> {
                  return Json.newObject()
                      .put("reference", history.uuid.keyRef)
                      .put("configUUID", history.configUuid.toString())
                      .put("timestamp", history.timestamp.toString());
                })
            .collect(Collectors.toList()));
  }

  public Result removeKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Removing key ref for customer %s with universe %s",
            customerUUID.toString(), universeUUID.toString()));
    keyManager.cleanupEncryptionAtRest(customerUUID, universeUUID);
    auditService().createAuditEntry(ctx(), request());
    return YWResults.YWSuccess.withMessage("Key ref was successfully removed");
  }

  public Result getCurrentKeyRef(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    KmsHistory activeKey = EncryptionAtRestUtil.getActiveKeyOrBadRequest(universeUUID);
    String keyRef = activeKey.uuid.keyRef;
    if (keyRef == null || keyRef.length() == 0) {
      throw new YWServiceException(
          BAD_REQUEST,
          String.format(
              "Could not retrieve key service for customer %s and universe %s",
              customerUUID.toString(), universeUUID.toString()));
    }
    return ApiResponse.success(Json.newObject().put("reference", keyRef));
  }
}
