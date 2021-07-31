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

import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertErrorNodeValue;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertYWSE;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthTokenAndBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.commissioner.tasks.params.KMSConfigTaskParams;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.kms.services.SmartKeyEARService;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import play.libs.Json;
import play.mvc.Result;

@RunWith(MockitoJUnitRunner.class)
public class EncryptionAtRestControllerTest extends FakeDBApplication {

  @Mock play.Configuration mockAppConfig;
  private Customer customer;
  private Users user;
  private Universe universe;
  private String authToken;

  String mockEncryptionKey = "RjZiNzVGekljNFh5Zmh0NC9FQ1dpM0FaZTlMVGFTbW1Wa1dnaHRzdDhRVT0=";
  String algorithm = "AES";
  int keySize = 256;
  String mockKid = "some_kId";

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    user = ModelFactory.testUser(customer);
    universe = ModelFactory.createUniverse();
    authToken = user.createAuthToken();
    String mockApiKey = "some_api_key";
    Map<String, String> authorizationHeaders =
        ImmutableMap.of("Authorization", String.format("Basic %s", mockApiKey));
    ObjectNode createReqPayload =
        Json.newObject()
            .put("name", universe.universeUUID.toString())
            .put("obj_type", algorithm)
            .put("key_size", keySize);
    ArrayNode keyOps = Json.newArray().add("EXPORT").add("APPMANAGEABLE");
    createReqPayload.set("key_ops", keyOps);
    Map<String, String> postReqHeaders =
        ImmutableMap.of(
            "Authorization",
            String.format("Bearer %s", mockApiKey),
            "Content-Type",
            "application/json");
    Map<String, String> getReqHeaders =
        ImmutableMap.of("Authorization", String.format("Bearer %s", mockApiKey));
    String getKeyUrl = String.format("https://some_base_url/crypto/v1/keys/%s/export", mockKid);
    Map<String, String> mockQueryParams =
        ImmutableMap.of("name", universe.universeUUID.toString(), "limit", "1");
    when(mockEARManager.getServiceInstance(eq("SMARTKEY"))).thenReturn(new SmartKeyEARService());
  }

  @Test
  public void testListKMSConfigs() {
    ModelFactory.createKMSConfig(customer.uuid, "SMARTKEY", Json.newObject());
    String url = "/api/v1/customers/" + customer.uuid + "/kms_configs";
    Result listResult = doRequestWithAuthToken("GET", url, authToken);
    assertOk(listResult);
    JsonNode json = Json.parse(contentAsString(listResult));
    assertTrue(json.isArray());
    assertEquals(1, json.size());
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testListEmptyConfigList() {
    String url = "/api/v1/customers/" + customer.uuid + "/kms_configs";
    Result listResult = doRequestWithAuthToken("GET", url, authToken);
    assertOk(listResult);
    JsonNode json = Json.parse(contentAsString(listResult));
    assertTrue(json.isArray());
    assertEquals(json.size(), 0);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testDeleteConfig() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(KMSConfigTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    ModelFactory.createKMSConfig(customer.uuid, "SMARTKEY", Json.newObject());
    String url = "/api/v1/customers/" + customer.uuid + "/kms_configs";
    Result listResult = doRequestWithAuthToken("GET", url, authToken);
    assertOk(listResult);
    JsonNode json = Json.parse(contentAsString(listResult));
    assertTrue(json.isArray());
    assertEquals(json.size(), 1);
    UUID kmsConfigUUID =
        UUID.fromString(((ArrayNode) json).get(0).get("metadata").get("configUUID").asText());
    url = "/api/v1/customers/" + customer.uuid + "/kms_configs/" + kmsConfigUUID.toString();
    Result deleteResult = doRequestWithAuthToken("DELETE", url, authToken);
    assertOk(deleteResult);
    json = Json.parse(contentAsString(deleteResult));
    UUID taskUUID = UUID.fromString(json.get("taskUUID").asText());
    assertNotNull(taskUUID);
    assertAuditEntry(1, customer.uuid);
  }

  @Ignore(
      "This test passes locally but fails on Jenkins due to Guice not injecting mocked ApiHelper for an unknown reason")
  @Test
  public void testCreateAndRecoverKey() {
    String kmsConfigUrl = "/api/customers/" + customer.uuid + "/kms_configs/SMARTKEY";
    ObjectNode kmsConfigReq =
        Json.newObject().put("base_url", "some_base_url").put("api_key", "some_api_token");
    Result createKMSResult =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", kmsConfigUrl, authToken, kmsConfigReq));
    assertOk(createKMSResult);
    String url =
        "/api/customers/"
            + customer.uuid
            + "/universes/"
            + universe.universeUUID
            + "/kms/SMARTKEY/create_key";
    ObjectNode createPayload =
        Json.newObject()
            .put("kms_provider", "SMARTKEY")
            .put("algorithm", algorithm)
            .put("key_size", Integer.toString(keySize));
    Result createKeyResult =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, createPayload));
    assertOk(createKeyResult);
    JsonNode json = Json.parse(contentAsString(createKeyResult));
    String keyValue = json.get("value").asText();
    assertEquals(keyValue, mockEncryptionKey);
    assertAuditEntry(2, customer.uuid);
  }

  @Test
  public void testCreateSMARTKEYKmsProviderWithInvalidAPIUrl() {
    String kmsConfigUrl = "/api/customers/" + customer.uuid + "/kms_configs/SMARTKEY";
    ObjectNode kmsConfigReq =
        Json.newObject().put("base_url", "some_base_url").put("api_key", "some_api_token");
    Result createKMSResult =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", kmsConfigUrl, authToken, kmsConfigReq));
    assertBadRequest(createKMSResult, "Invalid API URL.");
  }

  @Test
  public void testCreateSMARTKEYKmsProviderWithValidAPIUrlButInvalidAPIKey() {
    String kmsConfigUrl = "/api/customers/" + customer.uuid + "/kms_configs/SMARTKEY";
    ObjectNode kmsConfigReq =
        Json.newObject()
            .put("base_url", "api.amer.smartkey.io")
            .put("api_key", "some_api_token")
            .put("name", "test");
    Result createKMSResult =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", kmsConfigUrl, authToken, kmsConfigReq));
    assertBadRequest(createKMSResult, "Invalid API Key.");
  }

  @Test
  public void testCreateAwsKmsProviderWithInvalidCreds() {
    String kmsConfigUrl = "/api/customers/" + customer.uuid + "/kms_configs/AWS";
    ObjectNode kmsConfigReq =
        Json.newObject()
            .put(EncryptionAtRestController.AWS_ACCESS_KEY_ID_FIELDNAME, "aws_accesscode")
            .put(EncryptionAtRestController.AWS_REGION_FIELDNAME, "ap-south-1")
            .put(EncryptionAtRestController.AWS_SECRET_ACCESS_KEY_FIELDNAME, "aws_secretKey")
            .put("name", "test");
    CloudAPI mockCloudAPI = mock(CloudAPI.class);
    when(mockCloudAPIFactory.get(any())).thenReturn(mockCloudAPI);
    Result createKMSResult =
        assertYWSE(
            () -> doRequestWithAuthTokenAndBody("POST", kmsConfigUrl, authToken, kmsConfigReq));
    assertBadRequest(createKMSResult, "Invalid AWS Credentials.");
  }

  @Test
  public void testCreateAwsKmsProviderWithValidCreds() {
    String kmsConfigUrl = "/api/customers/" + customer.uuid + "/kms_configs/AWS";
    ObjectNode kmsConfigReq =
        Json.newObject()
            .put(EncryptionAtRestController.AWS_ACCESS_KEY_ID_FIELDNAME, "valid_accessKey")
            .put(EncryptionAtRestController.AWS_REGION_FIELDNAME, "ap-south-1")
            .put(EncryptionAtRestController.AWS_SECRET_ACCESS_KEY_FIELDNAME, "valid_secretKey")
            .put("name", "test");
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(any(TaskType.class), any(KMSConfigTaskParams.class)))
        .thenReturn(fakeTaskUUID);
    CloudAPI mockCloudAPI = mock(CloudAPI.class);
    when(mockCloudAPIFactory.get(any())).thenReturn(mockCloudAPI);
    when(mockCloudAPI.isValidCreds(any(), any())).thenReturn(true);
    Result createKMSResult =
        doRequestWithAuthTokenAndBody("POST", kmsConfigUrl, authToken, kmsConfigReq);
    assertOk(createKMSResult);
  }

  @Test
  public void testRecoverKeyNotFound() {
    UUID configUUID =
        ModelFactory.createKMSConfig(customer.uuid, "SMARTKEY", Json.newObject()).configUUID;
    String url = "/api/customers/" + customer.uuid + "/universes/" + universe.universeUUID + "/kms";
    ObjectNode body =
        Json.newObject()
            .put("reference", "NzNiYmY5M2UtNWYyNy00NzE3LTgyYTktMTVjYzUzMDIzZWRm")
            .put("configUUID", configUUID.toString());
    Result recoverKeyResult =
        assertYWSE(() -> doRequestWithAuthTokenAndBody("POST", url, authToken, body));
    JsonNode json = Json.parse(contentAsString(recoverKeyResult));
    String expectedErrorMsg =
        String.format("No universe key found for universe %s", universe.universeUUID.toString());
    assertErrorNodeValue(json, expectedErrorMsg);
    assertAuditEntry(0, customer.uuid);
  }
}
