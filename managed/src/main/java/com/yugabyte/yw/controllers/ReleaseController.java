// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.ReleaseManager;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.forms.ReleaseFormData;
import com.yugabyte.yw.forms.YWSuccess;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Result;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ReleaseController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(ReleaseController.class);

  @Inject
  ReleaseManager releaseManager;

  @Inject
  ValidatingFormFactory formFactory;

  public Result create(UUID customerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    Form<ReleaseFormData> formData = formFactory.getFormDataOrBadRequest(ReleaseFormData.class);
    ReleaseFormData releaseFormData = formData.get();
    try {
      releaseManager.addRelease(releaseFormData.version);
    } catch (RuntimeException re) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, re.getMessage());
    }
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return YWSuccess.asResult();
  }


  public Result list(UUID customerUUID, Boolean includeMetadata) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Map<String, Object> releases = releaseManager.getReleaseMetadata();
    // Filter out any deleted releases
    Map<String, Object> filtered = releases.entrySet().stream().filter(
      f -> !Json.toJson(f.getValue()).get("state").asText().equals("DELETED")
    ).collect(Collectors.toMap(p -> p.getKey(), p -> p.getValue()));
    if (includeMetadata) {
      return ApiResponse.success(filtered);
    } else {
      return ApiResponse.success(filtered.keySet());
    }
  }

  public Result update(UUID customerUUID, String version) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    ObjectNode formData;
    ReleaseManager.ReleaseMetadata m = releaseManager.getReleaseByVersion(version);
    if (m == null) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Release version: " + version);
    }
    formData = (ObjectNode)request().body().asJson();
    // For now we would only let the user change the state on their releases.
    if (formData.has("state")) {
      m.state = ReleaseManager.ReleaseState.valueOf(formData.get("state").asText());
      releaseManager.updateReleaseMetadata(version, m);
    } else {
      throw new YWServiceException(BAD_REQUEST, "Missing Required param: State");
    }
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData));
    return ApiResponse.success(m);
  }

  public Result refresh(UUID customerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    try {
      releaseManager.importLocalReleases();
    } catch (RuntimeException re) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, re.getMessage());
    }
    return YWSuccess.asResult();
  }
}
