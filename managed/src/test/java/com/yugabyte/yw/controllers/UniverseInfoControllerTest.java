/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.ApiUtils.getDefaultUserIntent;
import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertNotFound;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertPlatformException;
import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithCustomHeaders;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.common.PlacementInfoUtil.UNIVERSE_ALIVE_METRIC;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsBytes;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.queries.QueryHelper;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import junitparams.JUnitParamsRunner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import play.libs.Json;
import play.mvc.Result;

@Slf4j
@RunWith(JUnitParamsRunner.class)
public class UniverseInfoControllerTest extends UniverseControllerTestBase {

  @Test
  public void testGetMasterLeaderWithValidParams() {
    Universe universe = createUniverse(customer.getCustomerId());
    String url =
        "/api/customers/" + customer.uuid + "/universes/" + universe.universeUUID + "/leader";
    String host = "1.2.3.4";
    HostAndPort hostAndPort = HostAndPort.fromParts(host, 9000);
    when(mockClient.getLeaderMasterHostAndPort()).thenReturn(hostAndPort);
    when(mockService.getClient(any(), any())).thenReturn(mockClient);
    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "privateIP", host);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseStatusSuccess() {
    JsonNode fakeReturn = Json.newObject().set(UNIVERSE_ALIVE_METRIC, Json.newObject());
    when(mockMetricQueryHelper.query(anyList(), anyMap())).thenReturn(fakeReturn);
    Universe u = createUniverse("TestUniverse", customer.getCustomerId());
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/status";
    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertOk(result);
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testUniverseStatusError() {
    ObjectNode fakeReturn = Json.newObject().put("error", "foobar");
    when(mockMetricQueryHelper.query(anyList(), anyMap())).thenReturn(fakeReturn);

    Universe u = createUniverse(customer.getCustomerId());
    String url = "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/status";
    Result result = assertPlatformException(() -> doRequestWithAuthToken("GET", url, authToken));
    // TODO(API) - Should this be an http error and that too bad request?
    assertBadRequest(result, "foobar");
    assertAuditEntry(0, customer.uuid);
  }

  @Test
  public void testDownloadNodeLogs_NodeNotFound() {
    when(mockShellProcessHandler.run(anyList(), anyMap(), eq(true)))
        .thenReturn(new ShellResponse());

    Universe u = createUniverse(customer.getCustomerId());
    String url =
        "/api/customers/"
            + customer.uuid
            + "/universes/"
            + u.universeUUID
            + "/dummy_node/download_logs";
    Result result = assertPlatformException(() -> doRequestWithAuthToken("GET", url, authToken));
    assertNotFound(result, "dummy_node");
    assertAuditEntry(0, customer.uuid);
  }

  private byte[] createFakeLog(Path path) throws IOException {
    Random rng = new Random();
    File file = path.toFile();
    file.getParentFile().mkdirs();
    try (FileWriter out = new FileWriter(file)) {
      int sz = 1024 * 1024;
      byte[] arr = new byte[sz];
      rng.nextBytes(arr);
      out.write(new String(arr, StandardCharsets.UTF_8));
    }
    return FileUtils.readFileToByteArray(file);
  }

  @Test
  public void testDownloadNodeLogs() throws IOException {
    Path logPath =
        Paths.get(mockAppConfig.getString("yb.storage.path") + "/" + "10.0.0.1-logs.tar.gz");
    byte[] fakeLog = createFakeLog(logPath);
    when(mockShellProcessHandler.run(anyList(), anyMap(), eq(true)))
        .thenReturn(new ShellResponse());

    UniverseDefinitionTaskParams.UserIntent ui = getDefaultUserIntent(customer);
    String keyCode = "dummy_code";
    ui.accessKeyCode = keyCode;
    UUID uUUID = createUniverse(customer.getCustomerId()).universeUUID;
    Universe.saveDetails(uUUID, ApiUtils.mockUniverseUpdater(ui));

    Provider provider = Provider.get(customer.uuid, Common.CloudType.aws).get(0);
    AccessKey.KeyInfo keyInfo = new AccessKey.KeyInfo();
    keyInfo.sshPort = 1223;
    AccessKey.create(provider.uuid, keyCode, keyInfo);

    String url =
        "/api/customers/" + customer.uuid + "/universes/" + uUUID + "/host-n1/" + "download_logs";
    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertEquals(OK, result.status());
    byte[] actualContent = contentAsBytes(result, mat).toArray();
    assertArrayEquals(fakeLog, actualContent);
    assertAuditEntry(0, customer.uuid);
    assertFalse(logPath.toFile().exists());
  }

  @Test
  public void testGetSlowQueries() {
    String jsonMsg =
        "{\"ysql\":{\"errorCount\":0,\"queries\":[]},"
            + "\"ycql\":{\"errorCount\":0,\"queries\":[]}}";
    when(mockQueryHelper.slowQueries(any(), eq("yugabyte"), eq("foo-bar")))
        .thenReturn(Json.parse(jsonMsg));
    when(mockQueryHelper.slowQueries(any(), eq("yugabyte"), eq("yugabyte")))
        .thenThrow(new PlatformServiceException(BAD_REQUEST, "Incorrect Username or Password"));
    Universe u = createUniverse(customer.getCustomerId());
    String url =
        "/api/customers/" + customer.uuid + "/universes/" + u.universeUUID + "/slow_queries";
    Map<String, String> fakeRequestHeaders = new HashMap<>();
    fakeRequestHeaders.put("X-AUTH-TOKEN", authToken);
    fakeRequestHeaders.put("ysql-username", "yugabyte");
    fakeRequestHeaders.put("ysql-password", Util.encodeBase64("foo-bar"));

    Result result = doRequestWithCustomHeaders("GET", url, fakeRequestHeaders);
    assertOk(result);
    assertEquals(jsonMsg, contentAsString(result));

    fakeRequestHeaders.clear();
    fakeRequestHeaders.put("X-AUTH-TOKEN", authToken);
    fakeRequestHeaders.put("ysql-username", "yugabyte");
    fakeRequestHeaders.put("ysql-password", Util.encodeBase64("yugabyte"));

    result =
        assertPlatformException(() -> doRequestWithCustomHeaders("GET", url, fakeRequestHeaders));
  }

  @Test
  public void testSlowQueryLimit() {
    when(mockRuntimeConfig.getString(QueryHelper.QUERY_STATS_SLOW_QUERIES_ORDER_BY_KEY))
        .thenReturn("total_time");
    when(mockRuntimeConfig.getInt(QueryHelper.QUERY_STATS_SLOW_QUERIES_LIMIT_KEY)).thenReturn(200);
    QueryHelper queryHelper = new QueryHelper(null);
    String actualSql = queryHelper.slowQuerySqlWithLimit(mockRuntimeConfig);
    assertEquals(
        "SELECT a.rolname, t.datname, t.queryid, t.query, t.calls, t.total_time, t.rows,"
            + " t.min_time, t.max_time, t.mean_time, t.stddev_time, t.local_blks_hit,"
            + " t.local_blks_written FROM pg_authid a JOIN (SELECT * FROM pg_stat_statements s"
            + " JOIN pg_database d ON s.dbid = d.oid) t ON a.oid = t.userid ORDER BY"
            + " t.total_time DESC LIMIT 200",
        actualSql);
  }

  @Test
  public void testTriggerHealthCheck() {
    when(mockRuntimeConfig.getBoolean("yb.cloud.enabled")).thenReturn(true);
    Universe u = createUniverse(customer.getCustomerId());
    String url =
        "/api/customers/"
            + customer.uuid
            + "/universes/"
            + u.universeUUID
            + "/trigger_health_check";

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
    log.info("Before: {}", before);

    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertOk(result);

    String json = contentAsString(result);
    log.info("Returned JSON response: {}", json);

    assertTrue(json.contains("timestamp"));
    ObjectNode node = (ObjectNode) Json.parse(json);

    String ts = node.get("timestamp").asText();
    assertNotNull(ts);
    log.info("Parsed TS string: {}", ts);

    OffsetDateTime tsFromResponse = OffsetDateTime.parse(ts);
    Duration d = Duration.between(before, tsFromResponse);
    log.info("Parsed duration: {}", d);

    assertTrue(d.toMinutes() < 60);
  }
}
