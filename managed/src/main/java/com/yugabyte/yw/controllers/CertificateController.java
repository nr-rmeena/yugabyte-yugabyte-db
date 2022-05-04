package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.CertificateHelper;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.forms.CertificateParams;
import com.yugabyte.yw.forms.ClientCertParams;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Result;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class CertificateController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(CertificateController.class);

  @Inject
  play.Configuration appConfig;

  @Inject
  ValidatingFormFactory formFactory;

  public Result upload(UUID customerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Form<CertificateParams> formData = formFactory.getFormDataOrBadRequest(CertificateParams.class);

    Date certStart = new Date(formData.get().certStart);
    Date certExpiry = new Date(formData.get().certExpiry);
    String label = formData.get().label;
    CertificateInfo.Type certType = formData.get().certType;
    String certContent = formData.get().certContent;
    String keyContent = formData.get().keyContent;
    CertificateParams.CustomCertInfo customCertInfo = formData.get().customCertInfo;
    if (certType == CertificateInfo.Type.SelfSigned) {
      if (certContent == null || keyContent == null) {
        throw new YWServiceException(BAD_REQUEST, "Certificate or Keyfile can't be null.");
      }
    } else {
      if (customCertInfo == null) {
        throw new YWServiceException(BAD_REQUEST, "Custom Cert Info must be provided.");
      } else if (customCertInfo.nodeCertPath == null || customCertInfo.nodeKeyPath == null ||
                 customCertInfo.rootCertPath == null) {
        throw new YWServiceException(BAD_REQUEST, "Custom Cert Paths can't be empty.");
      }
    }
    LOG.info("CertificateController: upload cert label {}, type {}", label, certType);
    UUID certUUID = CertificateHelper.uploadRootCA(
                      label, customerUUID, appConfig.getString("yb.storage.path"),
                      certContent, keyContent, certStart, certExpiry, certType,
                      customCertInfo
                    );
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return ApiResponse.success(certUUID);
  }

  public Result getClientCert(UUID customerUUID, UUID rootCA) {
    Form<ClientCertParams> formData = formFactory.getFormDataOrBadRequest(ClientCertParams.class);
    Customer.getOrBadRequest(customerUUID);
    Long certTimeMillis = formData.get().certStart;
    Long certExpiryMillis = formData.get().certExpiry;
    Date certStart = certTimeMillis != 0L ? new Date(certTimeMillis) : null;
    Date certExpiry = certExpiryMillis != 0L ? new Date(certExpiryMillis) : null;

    JsonNode result = CertificateHelper.createClientCertificate(
        rootCA, null, formData.get().username, certStart, certExpiry);
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return ApiResponse.success(result);
  }

  public Result getRootCert(UUID customerUUID, UUID rootCA) {
    Customer.getOrBadRequest(customerUUID);
    CertificateInfo.getOrBadRequest(rootCA, customerUUID);

    String certContents = CertificateHelper.getCertPEMFileContents(rootCA);
    auditService().createAuditEntry(ctx(), request());
    ObjectNode result = Json.newObject();
    result.put(CertificateHelper.ROOT_CERT, certContents);
    return ApiResponse.success(result);
  }

  public Result list(UUID customerUUID) {
    List<CertificateInfo> certs = CertificateInfo.getAll(customerUUID);
    return ApiResponse.success(certs);
  }

  public Result get(UUID customerUUID, String label) {
    CertificateInfo cert = CertificateInfo.getOrBadRequest(label);
    return ApiResponse.success(cert.uuid);
  }

  public Result delete(UUID customerUUID, UUID reqCertUUID) {
    CertificateInfo.delete(reqCertUUID, customerUUID);
    auditService().createAuditEntry(ctx(), request());
    LOG.info("Successfully deleted the certificate:" + reqCertUUID);
    return YWResults.YWSuccess.empty();
  }

  public Result updateEmptyCustomCert(UUID customerUUID, UUID rootCA) {
    Form<CertificateParams> formData = formFactory.getFormDataOrBadRequest(
        CertificateParams.class);
    Customer.getOrBadRequest(customerUUID);
    CertificateInfo certificate = CertificateInfo.getOrBadRequest(rootCA, customerUUID);
    CertificateParams.CustomCertInfo customCertInfo = formData.get().customCertInfo;
    certificate.setCustomCertInfo(customCertInfo, rootCA, customerUUID);
    return ApiResponse.success(certificate);
  }
}
