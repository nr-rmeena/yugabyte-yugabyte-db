// Copyright 2020 YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.ConfigHelper.ConfigType.Security;
import static com.yugabyte.yw.forms.PlatformResults.withData;
import static com.yugabyte.yw.models.Users.Role;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.yugabyte.yw.common.ApiHelper;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDestinationService;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.password.PasswordPolicyService;
import com.yugabyte.yw.forms.CustomerLoginFormData;
import com.yugabyte.yw.forms.CustomerRegisterFormData;
import com.yugabyte.yw.forms.PasswordPolicyFormData;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.SetSecurityFormData;
import com.yugabyte.yw.forms.UniverseResp;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import javax.persistence.PersistenceException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.comparator.LastModifiedFileComparator;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.input.ReversedLinesFileReader;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.java.Secure;
import org.pac4j.play.store.PlaySessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unix4j.Unix4j;
import org.unix4j.unix.grep.GrepOption;
import org.unix4j.unix.Sort;
import play.Configuration;
import play.Environment;
import play.data.Form;
import play.db.ebean.Transactional;
import play.libs.Json;
import play.libs.concurrent.HttpExecutionContext;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.mvc.Http;
import play.mvc.Http.Cookie;
import play.mvc.Result;
import play.mvc.Results;
import play.mvc.With;

@Api(value = "Session management")
@Slf4j
public class SessionController extends AbstractPlatformController {

  public static final Logger LOG = LoggerFactory.getLogger(SessionController.class);

  static final Pattern PROXY_PATTERN = Pattern.compile("^(.+):([0-9]{1,5})/.*$");

  @Inject private ValidatingFormFactory formFactory;

  @Inject private Configuration appConfig;

  @Inject private ConfigHelper configHelper;

  @Inject private Environment environment;

  @Inject private WSClient ws;

  @Inject private PlaySessionStore playSessionStore;

  @Inject private ApiHelper apiHelper;

  @Inject private PasswordPolicyService passwordPolicyService;

  @Inject private AlertConfigurationService alertConfigurationService;

  @Inject private AlertDestinationService alertDestinationService;

  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  @Inject private HttpExecutionContext ec;

  public static final String AUTH_TOKEN = "authToken";
  public static final String API_TOKEN = "apiToken";
  public static final String CUSTOMER_UUID = "customerUUID";
  private static final Integer FOREVER = 2147483647;

  private CommonProfile getProfile() {
    final PlayWebContext context = new PlayWebContext(ctx(), playSessionStore);
    final ProfileManager<CommonProfile> profileManager = new ProfileManager<>(context);
    return profileManager
        .get(true)
        .orElseThrow(
            () -> new PlatformServiceException(INTERNAL_SERVER_ERROR, "Unable to get profile"));
  }

  @ApiModel(description = "Session information")
  @RequiredArgsConstructor
  public static class SessionInfo {

    @ApiModelProperty(value = "Auth token")
    public final String authToken;

    @ApiModelProperty(value = "API token")
    public final String apiToken;

    @ApiModelProperty(value = "Customer UUID")
    public final UUID customerUUID;

    @ApiModelProperty(value = "User UUID")
    public final UUID userUUID;
  }

  @ApiOperation(
      nickname = "getSessionInfo",
      value = "Get current user/customer uuid auth/api token",
      authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH),
      response = SessionInfo.class)
  @With(TokenAuthenticator.class)
  public Result getSessionInfo() {
    Users user = (Users) Http.Context.current().args.get("user");
    Customer cust = Customer.get(user.customerUUID);
    Cookie authCookie = request().cookie(AUTH_TOKEN);
    SessionInfo sessionInfo =
        new SessionInfo(
            authCookie == null ? null : authCookie.value(),
            user.getApiToken(),
            cust.uuid,
            user.uuid);
    return withData(sessionInfo);
  }

  @Data
  static class CustomerCountResp {

    final int count;
  }

  @ApiOperation(value = "customerCount", response = CustomerCountResp.class)
  public Result customerCount() {
    int customerCount = Customer.find.all().size();
    return PlatformResults.withData(new CustomerCountResp(customerCount));
  }

  @ApiOperation(value = "appVersion", responseContainer = "Map", response = String.class)
  public Result appVersion() {
    return withData(configHelper.getConfig(ConfigHelper.ConfigType.SoftwareVersion));
  }

  @Data
  static class LogData {

    final List<String> lines;
  }

  @ApiOperation(value = "getLogs", response = LogData.class)
  @With(TokenAuthenticator.class)
  public Result getLogs(Integer maxLines) {
    String appHomeDir = appConfig.getString("application.home", ".");
    String logDir = appConfig.getString("log.override.path", String.format("%s/logs", appHomeDir));
    File file = new File(String.format("%s/application.log", logDir));
    // TODO(bogdan): This is not really pagination friendly as it re-reads everything all the time.
    // TODO(bogdan): Need to figure out if there's a rotation-friendly log-reader..
    try (ReversedLinesFileReader reader = new ReversedLinesFileReader(file)) {
      int index = 0;
      List<String> lines = new ArrayList<>();
      while (index++ < maxLines) {
        String line = reader.readLine();
        if (line == null) { // No more lines.
          break;
        }
        lines.add(line);
      }
      return PlatformResults.withData(new LogData(lines));
    } catch (IOException ex) {
      LOG.error("Log file open failed.", ex);
      throw new PlatformServiceException(
          INTERNAL_SERVER_ERROR, "Could not open log file with error " + ex.getMessage());
    }
  }

  @ApiOperation(value = "getFilteredLogs", response = LogData.class)
  @With(TokenAuthenticator.class)
  public Result getFilteredLogs(Integer maxLines, String universeName, String queryRegex) {
    LOG.debug(
        "getFilteredLogs: maxLines - {}, universeName - {}, queryRegex - {}",
        maxLines,
        universeName,
        queryRegex);
    String appHomeDir = appConfig.getString("application.home", ".");
    String logDir = appConfig.getString("log.override.path", String.format("%s/logs", appHomeDir));
    List<String> regexBuilder = new ArrayList<>();
    String universeUUID = null;
    Universe universe = null;

    if (universeName != null) {
      universe = Universe.getUniverseByName(universeName);
      if (universe == null) {
        LOG.error("Universe {} not found", universeName);
        throw new PlatformServiceException(BAD_REQUEST, "Universe name given does not exist");
      }
      universeUUID = universe.universeUUID.toString();
      regexBuilder.add(universeUUID);
    }

    if (queryRegex != null) {
      try {
        Pattern.compile(queryRegex);
      } catch (PatternSyntaxException exception) {
        LOG.error("Invalid regular expression given: {}", queryRegex);
        throw new PlatformServiceException(BAD_REQUEST, "Invalid regular expression given");
      }
      regexBuilder.add(queryRegex);
    }

    String grepRegex = buildRegexString(regexBuilder);

    File file = new File(String.format("%s/application.log", logDir));
    if (!file.exists()) {
      throw new PlatformServiceException(BAD_REQUEST, "Could not find application.log file.");
    }
    List<String> lines =
        Unix4j.fromFile(String.format("%s/application.log", logDir))
            .grep(GrepOption.ignoreCase, grepRegex)
            .tail(maxLines)
            .sort(Sort.Options.reverse)
            .toStringList();

    if (lines.size() >= maxLines) {
      return PlatformResults.withData(new LogData(lines));
    }
    File dir = new File(logDir);
    FileFilter fileFilter = new WildcardFileFilter("*.gz", IOCase.INSENSITIVE);
    File[] gzFileList = dir.listFiles(fileFilter);
    Arrays.sort(gzFileList, LastModifiedFileComparator.LASTMODIFIED_REVERSE);
    for (File gzfile : gzFileList) {
      if (gzfile.isFile()) {
        try {
          final InputStream gzStream = new GZIPInputStream(new FileInputStream(gzfile));
          int linesRemaining = maxLines - lines.size();
          List<String> newLines =
              Unix4j.from(gzStream)
                  .grep(GrepOption.ignoreCase, grepRegex)
                  .tail(linesRemaining)
                  .sort(Sort.Options.reverse)
                  .toStringList();
          lines.addAll(newLines);
          if (lines.size() >= maxLines) {
            break;
          }
        } catch (FileNotFoundException ex) {
          LOG.error("Gz file not found.", ex);
          throw new PlatformServiceException(
              INTERNAL_SERVER_ERROR, "Could not find gz file with error " + ex.getMessage());
        } catch (IOException ex) {
          LOG.error("Log file open failed.", ex);
          throw new PlatformServiceException(
              INTERNAL_SERVER_ERROR, "Could not open gz file with error " + ex.getMessage());
        }
      }
    }
    return PlatformResults.withData(new LogData(lines));
  }

  public String buildRegexString(List<String> regexBuilder) {
    String regexString = "";
    if (regexBuilder.size() == 0) {
      return regexString;
    }

    List<List<String>> permutedStrings = new ArrayList<>();
    permute(regexBuilder, 0, permutedStrings);

    List<String> regexArr = new ArrayList<>();
    for (List<String> list : permutedStrings) {
      regexArr.add(String.join(".*", list));
    }
    regexArr = regexArr.stream().map(v -> ".*" + v + ".*").collect(Collectors.toList());

    regexString = String.join("|", regexArr);
    return regexString;
  }

  public void permute(List<String> arr, int k, List<List<String>> permutations) {
    for (int i = k; i < arr.size(); i++) {
      Collections.swap(arr, i, k);
      permute(arr, k + 1, permutations);
      Collections.swap(arr, k, i);
    }
    if (k == arr.size() - 1) {
      List<String> copy = new ArrayList<String>(arr);
      permutations.add(copy);
    }
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  public Result login() {
    boolean useOAuth = appConfig.getBoolean("yb.security.use_oauth", false);
    if (useOAuth) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Platform login not supported when using SSO.");
    }

    CustomerLoginFormData data =
        formFactory.getFormDataOrBadRequest(CustomerLoginFormData.class).get();
    Users user = Users.authWithPassword(data.getEmail().toLowerCase(), data.getPassword());

    if (user == null) {
      throw new PlatformServiceException(UNAUTHORIZED, "Invalid User Credentials");
    }
    Customer cust = Customer.get(user.customerUUID);

    String authToken = user.createAuthToken();
    SessionInfo sessionInfo = new SessionInfo(authToken, null, cust.uuid, user.uuid);
    response()
        .setCookie(
            Http.Cookie.builder(AUTH_TOKEN, authToken)
                .withSecure(ctx().request().secure())
                .build());
    response()
        .setCookie(
            Http.Cookie.builder("customerId", cust.uuid.toString())
                .withSecure(ctx().request().secure())
                .build());
    response()
        .setCookie(
            Http.Cookie.builder("userId", user.uuid.toString())
                .withSecure(ctx().request().secure())
                .build());
    return withData(sessionInfo);
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  public Result getPlatformConfig() {
    boolean useOAuth = appConfig.getBoolean("yb.security.use_oauth", false);
    String platformConfig = "window.YB_Platform_Config = window.YB_Platform_Config || %s";
    ObjectNode responseJson = Json.newObject();
    responseJson.put("use_oauth", useOAuth);
    platformConfig = String.format(platformConfig, responseJson.toString());
    return ok(platformConfig);
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  @Secure(clients = "OidcClient")
  public Result thirdPartyLogin() {
    CommonProfile profile = getProfile();
    String emailAttr = appConfig.getString("yb.security.oidcEmailAttribute", "");
    String email;
    if (emailAttr.equals("")) {
      email = profile.getEmail();
    } else {
      email = (String) profile.getAttribute(emailAttr);
    }
    Users user = Users.getByEmail(email.toLowerCase());
    if (user == null) {
      final PlayWebContext context = new PlayWebContext(ctx(), playSessionStore);
      final ProfileManager<CommonProfile> profileManager = new ProfileManager<>(context);
      profileManager.logout();
      playSessionStore.destroySession(context);
    } else {
      Customer cust = Customer.get(user.customerUUID);
      ctx().args.put("customer", cust);
      ctx().args.put("user", user);
      response()
          .setCookie(
              Http.Cookie.builder("customerId", cust.uuid.toString())
                  .withSecure(ctx().request().secure())
                  .build());
      response()
          .setCookie(
              Http.Cookie.builder("userId", user.uuid.toString())
                  .withSecure(ctx().request().secure())
                  .build());
    }
    if (environment.isDev()) {
      return redirect("http://localhost:3000/");
    } else {
      return redirect("/");
    }
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  public Result insecure_login() {
    List<Customer> allCustomers = Customer.getAll();
    if (allCustomers.size() != 1) {
      throw new PlatformServiceException(
          UNAUTHORIZED, "Cannot allow insecure with multiple customers.");
    }
    String securityLevel =
        (String) configHelper.getConfig(ConfigHelper.ConfigType.Security).get("level");
    if (securityLevel != null && securityLevel.equals("insecure")) {
      List<Users> users = Users.getAllReadOnly();
      if (users.isEmpty()) {
        throw new PlatformServiceException(UNAUTHORIZED, "No read only customer exists.");
      }
      Users user = users.get(0);
      if (user == null) {
        throw new PlatformServiceException(UNAUTHORIZED, "Invalid User saved.");
      }
      String apiToken = user.getApiToken();
      if (apiToken == null || apiToken.isEmpty()) {
        apiToken = user.upsertApiToken();
      }

      SessionInfo sessionInfo = new SessionInfo(null, apiToken, user.customerUUID, user.uuid);
      response()
          .setCookie(
              Http.Cookie.builder(API_TOKEN, apiToken)
                  .withSecure(ctx().request().secure())
                  .build());
      return withData(sessionInfo);
    }
    throw new PlatformServiceException(UNAUTHORIZED, "Insecure login unavailable.");
  }

  // Any changes to security should be authenticated.
  @ApiOperation(value = "UI_ONLY", hidden = true)
  @With(TokenAuthenticator.class)
  public Result set_security(UUID customerUUID) {
    Form<SetSecurityFormData> formData =
        formFactory.getFormDataOrBadRequest(SetSecurityFormData.class);
    List<Customer> allCustomers = Customer.getAll();
    if (allCustomers.size() != 1) {
      throw new PlatformServiceException(
          UNAUTHORIZED, "Cannot allow insecure with multiple customers.");
    }

    SetSecurityFormData data = formData.get();
    configHelper.loadConfigToDB(Security, ImmutableMap.of("level", data.level));
    if (data.level.equals("insecure")) {
      Users user = (Users) Http.Context.current().args.get("user");
      String apiToken = user.getApiToken();
      if (apiToken == null || apiToken.isEmpty()) {
        user.upsertApiToken();
      }

      try {
        InputStream featureStream = environment.resourceAsStream("ossFeatureConfig.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode features = mapper.readTree(featureStream);
        Customer.get(customerUUID).upsertFeatures(features);
      } catch (IOException e) {
        LOG.error("Failed to parse sample feature config file for OSS mode.");
      }
    }
    return YBPSuccess.empty();
  }

  @With(TokenAuthenticator.class)
  @ApiOperation(value = "UI_ONLY", hidden = true, response = SessionInfo.class)
  public Result api_token(UUID customerUUID) {
    Users user = (Users) Http.Context.current().args.get("user");

    if (user == null) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Could not find User from given credentials.");
    }

    String apiToken = user.upsertApiToken();
    SessionInfo sessionInfo = new SessionInfo(null, apiToken, customerUUID, user.uuid);
    response()
        .setCookie(
            Http.Cookie.builder(API_TOKEN, apiToken)
                .withSecure(ctx().request().secure())
                .withMaxAge(FOREVER)
                .build());
    return withData(sessionInfo);
  }

  @ApiOperation(value = "UI_ONLY", hidden = true, response = SessionInfo.class)
  @Transactional
  public Result register() {
    CustomerRegisterFormData data =
        formFactory.getFormDataOrBadRequest(CustomerRegisterFormData.class).get();
    boolean multiTenant = appConfig.getBoolean("yb.multiTenant", false);
    boolean useOAuth = appConfig.getBoolean("yb.security.use_oauth", false);
    int customerCount = Customer.getAll().size();
    if (!multiTenant && customerCount >= 1) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Cannot register multiple accounts in Single tenancy.");
    }
    if (useOAuth && customerCount >= 1) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Cannot register multiple accounts with SSO enabled platform.");
    }
    if (customerCount == 0) {
      return withData(registerCustomer(data, true));
    } else {
      if (TokenAuthenticator.superAdminAuthentication(ctx())) {
        return withData(registerCustomer(data, false));
      } else {
        throw new PlatformServiceException(BAD_REQUEST, "Only Super Admins can register tenant.");
      }
    }
  }

  public Result getPasswordPolicy(UUID customerUUID) {
    PasswordPolicyFormData validPolicy = passwordPolicyService.getPasswordPolicyData(customerUUID);
    if (validPolicy != null) {
      return PlatformResults.withData(validPolicy);
    }
    throw new PlatformServiceException(INTERNAL_SERVER_ERROR, "Failed to get validation policy");
  }

  private SessionInfo registerCustomer(CustomerRegisterFormData data, boolean isSuper) {
    Customer cust = Customer.create(data.getCode(), data.getName());
    Role role = Role.Admin;
    if (isSuper) {
      role = Role.SuperAdmin;
    }
    passwordPolicyService.checkPasswordPolicy(cust.getUuid(), data.getPassword());

    alertDestinationService.createDefaultDestination(cust.uuid);
    alertConfigurationService.createDefaultConfigs(cust);

    Users user = Users.createPrimary(data.getEmail(), data.getPassword(), role, cust.uuid);
    String authToken = user.createAuthToken();
    SessionInfo sessionInfo = new SessionInfo(authToken, null, user.customerUUID, user.uuid);
    response()
        .setCookie(
            Http.Cookie.builder(AUTH_TOKEN, sessionInfo.authToken)
                .withSecure(ctx().request().secure())
                .build());
    // When there is no authenticated user in context; we just pretend that the user
    // created himself for auditing purpose.
    ctx().args.putIfAbsent("user", user);
    auditService().createAuditEntry(ctx(), request());
    return sessionInfo;
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  @With(TokenAuthenticator.class)
  public Result logout() {
    response().discardCookie(AUTH_TOKEN);
    Users user = (Users) Http.Context.current().args.get("user");
    if (user != null) {
      user.deleteAuthToken();
    }
    return YBPSuccess.empty();
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  public Result getUITheme() {
    try {
      return Results.ok(environment.resourceAsStream("theme/theme.css"));
    } catch (NullPointerException ne) {
      throw new PlatformServiceException(BAD_REQUEST, "Theme file doesn't exists.");
    }
  }

  @ApiOperation(value = "UI_ONLY", hidden = true)
  @With(TokenAuthenticator.class)
  public CompletionStage<Result> proxyRequest(UUID universeUUID, String requestUrl) {

    LOG.trace("proxyRequest for universe {} : {}", universeUUID, requestUrl);

    Universe universe = Universe.getOrBadRequest(universeUUID);
    // Validate that the request is of <ip/hostname>:<port> format
    Matcher matcher = PROXY_PATTERN.matcher(requestUrl);
    if (!matcher.matches()) {
      LOG.error("Request {} does not match expected pattern", requestUrl);
      throw new PlatformServiceException(BAD_REQUEST, "Invalid proxy request");
    }

    // Extract host + port from request
    String host = matcher.group(1);
    String port = matcher.group(2);
    String addr = String.format("%s:%s", host, port);

    // Validate that the proxy request is for a node from the specified universe
    if (!universe.nodeExists(host, Integer.parseInt(port))) {
      LOG.error("Universe {} does not contain node address {}", universeUUID, addr);
      throw new PlatformServiceException(BAD_REQUEST, "Invalid proxy request");
    }

    // Add query params to proxied request
    final String finalRequestUrl = apiHelper.buildUrl(requestUrl, request().queryString());

    // Make the request
    Duration timeout =
        runtimeConfigFactory.globalRuntimeConf().getDuration("yb.proxy_endpoint_timeout");
    WSRequest request =
        ws.url("http://" + finalRequestUrl)
            .setRequestTimeout(timeout)
            .addHeader(play.mvc.Http.HeaderNames.ACCEPT_ENCODING, "gzip");
    // Accept-Encoding: gzip causes the master/tserver to typically return compressed responses,
    // however Play doesn't return gzipped responses right now

    return request
        .get()
        .handle(
            (response, ex) -> {
              if (null != ex) {
                return internalServerError(ex.getMessage());
              }

              // Format the response body
              if (null != response && response.getStatus() == 200) {
                Result result;
                String url = request.getUrl();
                if (url.contains(".png") || url.contains(".ico") || url.contains("fontawesome")) {
                  result = ok(response.getBodyAsBytes().toArray());
                } else {
                  result = ok(apiHelper.replaceProxyLinks(response.getBody(), universeUUID, addr));
                }

                // Set response headers
                for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
                  if (!entry.getKey().equals(play.mvc.Http.HeaderNames.CONTENT_LENGTH)
                      && !entry.getKey().equals(play.mvc.Http.HeaderNames.CONTENT_TYPE)
                      && !entry.getKey().equals(play.mvc.Http.HeaderNames.TRANSFER_ENCODING)) {
                    result = result.withHeader(entry.getKey(), String.join(",", entry.getValue()));
                  }
                }

                return result.as(response.getContentType());
              } else {
                String errorMsg = "unknown error processing proxy request " + requestUrl;
                if (null != response) {
                  errorMsg = response.getStatusText();
                }
                return internalServerError(errorMsg);
              }
            });
  }
}
