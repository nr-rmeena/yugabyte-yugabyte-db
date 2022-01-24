// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.helpers;

import static com.yugabyte.yw.common.ThrownMatcher.thrown;
import static com.yugabyte.yw.models.helpers.CustomerConfigConsts.BACKUP_LOCATION_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigConsts.REGION_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigConsts.REGION_LOCATIONS_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigConsts.REGION_LOCATION_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.AWS_ACCESS_KEY_ID_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.AWS_SECRET_ACCESS_KEY_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.GCS_CREDENTIALS_JSON_FIELDNAME;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.NAME_GCS;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.NAME_S3;
import static com.yugabyte.yw.models.helpers.CustomerConfigValidator.fieldFullName;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.yugabyte.yw.common.BeanValidator;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.models.CustomerConfig;
import com.yugabyte.yw.models.CustomerConfig.ConfigType;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import play.libs.Json;

@RunWith(JUnitParamsRunner.class)
public class CustomerConfigValidatorTest extends FakeDBApplication {

  @Rule public MockitoRule rule = MockitoJUnit.rule();

  private CustomerConfigValidator customerConfigValidator;

  private List<String> allowedBuckets = new ArrayList<>();

  @Before
  public void setUp() {
    customerConfigValidator =
        new LocalCustomerConfigValidator(
            app.injector().instanceOf(BeanValidator.class), allowedBuckets);
  }

  @Test
  // @formatter:off
  @Parameters({
    "NFS, BACKUP_LOCATION, /tmp, true",
    "NFS, BACKUP_LOCATION, tmp, false",
    "NFS, BACKUP_LOCATION, /mnt/storage, true",
    "NFS, BACKUP_LOCATION, //, true",
    "NFS, BACKUP_LOCATION, $(ping -c1 google.com.ru > /tmp/ping_log)/tmp/some/nfs/dir, false",
    "NFS, BACKUP_LOCATION,, false",
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, true",
    "S3, AWS_HOST_BASE, s3://backups.yugabyte.com/test/itest, false", // BACKUP_LOCATION undefined
    "S3, BACKUP_LOCATION, s3.amazonaws.com, true",
    "S3, BACKUP_LOCATION, ftp://s3.amazonaws.com, false",
    "S3, BACKUP_LOCATION,, false",
    "GCS, BACKUP_LOCATION, gs://itest-backup, true",
    "GCS, BACKUP_LOCATION, gs://itest-backup/test, true",
    "GCS, BACKUP_LOCATION, gcp.test.com, true",
    "GCS, BACKUP_LOCATION, ftp://gcp.test.com, false",
    "GCS, BACKUP_LOCATION,, false",
    "AZ, BACKUP_LOCATION, https://www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, http://www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, www.microsoft.com/azure, true",
    "AZ, BACKUP_LOCATION, ftp://www.microsoft.com/azure, false",
    "AZ, BACKUP_LOCATION,, false",
  })
  // @formatter:on
  public void testValidateDataContent_Storage_OneParamToCheck(
      String storageType, String fieldName, String fieldValue, boolean expectedResult) {
    ObjectNode data = Json.newObject().put(fieldName, fieldValue);
    CustomerConfig config = createConfig(ConfigType.STORAGE, storageType, data);
    if (expectedResult) {
      customerConfigValidator.validateConfig(config);
    } else {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(PlatformServiceException.class));
    }
  }

  @Test
  // @formatter:off
  @Parameters({
    // location - correct, aws_host_base - empty -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, AWS_HOST_BASE,, true",
    // location - correct, aws_host_base - incorrect -> disallowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, ftp://s3.amazonaws.com, false",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, s3.amazonaws.com, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, AWS_HOST_BASE, s3.amazonaws.com, true",
    // location - correct, aws_host_base(for S3 compatible storage) - correct -> allowed
    "S3, BACKUP_LOCATION, s3://false, AWS_HOST_BASE, http://fake-localhost:9000, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, AWS_HOST_BASE, s3.amazonaws.com:443, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, AWS_HOST_BASE, minio.rmn.local:30000, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, AWS_HOST_BASE, https://s3.amazonaws.com, true",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, "
        + " AWS_HOST_BASE, https://s3.amazonaws.com:443, true",
    // location - correct, aws_host_base(negative port value) - incorrect -> disallowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, "
        + " AWS_HOST_BASE, http://s3.amazonaws.com:-443, false",
    // location - correct, aws_host_base(negative port value) - incorrect -> disallowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com, AWS_HOST_BASE, s3.amazonaws.com:-443, false",
    // location - correct, aws_host_base - correct -> allowed
    "S3, BACKUP_LOCATION, s3://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, cloudstorage.onefs.dell.com, true",
    // location - incorrect, aws_host_base - correct -> disallowed
    "S3, BACKUP_LOCATION, ftp://backups.yugabyte.com/test/itest, "
        + "AWS_HOST_BASE, s3.amazonaws.com, false",
    // location - incorrect, aws_host_base - empty -> disallowed
    "S3, BACKUP_LOCATION, ftp://backups.yugabyte.com/test/itest, AWS_HOST_BASE,, false",
    // location - empty, aws_host_base - correct -> disallowed
    "S3, BACKUP_LOCATION,, AWS_HOST_BASE, s3.amazonaws.com, false",
    // location - empty, aws_host_base - empty -> disallowed
    "S3, BACKUP_LOCATION,, AWS_HOST_BASE,, false",
  })
  // @formatter:on
  public void testValidateDataContent_Storage_TwoParamsToCheck(
      String storageType,
      String fieldName1,
      String fieldValue1,
      String fieldName2,
      String fieldValue2,
      boolean expectedResult) {
    ObjectNode data = Json.newObject();
    data.put(fieldName1, fieldValue1);
    data.put(fieldName2, fieldValue2);
    CustomerConfig config = createConfig(ConfigType.STORAGE, storageType, data);
    if (expectedResult) {
      customerConfigValidator.validateConfig(config);
    } else {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(PlatformServiceException.class));
    }
  }

  @Parameters({
    // Check invalid AWS Credentials -> disallowed.
    "s3://test, The AWS Access Key Id you provided does not exist in our records., true",
    // BACKUP_LOCATION - incorrect -> disallowed.
    "https://abc, Invalid s3UriPath format: https://abc, false",
    // Valid case.
    "s3://test, null, false",
  })
  @Test
  public void testValidateDataContent_Storage_S3PreflightCheckValidator(
      String backupLocation, @Nullable String expectedMessage, boolean refuseKeys) {
    ((LocalCustomerConfigValidator) customerConfigValidator).setRefuseKeys(refuseKeys);
    ObjectNode data = Json.newObject();
    data.put(BACKUP_LOCATION_FIELDNAME, backupLocation);
    data.put(AWS_ACCESS_KEY_ID_FIELDNAME, "testAccessKey");
    data.put(AWS_SECRET_ACCESS_KEY_FIELDNAME, "SecretKey");
    CustomerConfig config = createConfig(ConfigType.STORAGE, NAME_S3, data);
    if ((expectedMessage != null) && !expectedMessage.equals("")) {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(
              PlatformServiceException.class,
              "errorJson: {\""
                  + fieldFullName(BACKUP_LOCATION_FIELDNAME)
                  + "\":[\""
                  + expectedMessage
                  + "\"]}"));
    } else {
      allowedBuckets.add(backupLocation.substring(5).split("/", 2)[0]);
      customerConfigValidator.validateConfig(config);
    }
  }

  @Parameters({
    // Valid case with location URL equal to backup URL.
    "s3://test, null",
    // Valid case with location URL different from backup URL.
    "s3://test2, null",
    // Invalid location URL (wrong format).
    "ftp://test2, Invalid field value 'ftp://test2'.",
    // Valid bucket.
    "s3://test2, S3 URI path s3://test2 doesn't exist",
  })
  @Test
  public void testValidateDataContent_Storage_S3PreflightCheckValidator_RegionLocation(
      String regionLocation, @Nullable String expectedMessage) {
    ObjectNode data = Json.newObject();
    data.put(BACKUP_LOCATION_FIELDNAME, "s3://test");
    allowedBuckets.add("test");
    data.put(AWS_ACCESS_KEY_ID_FIELDNAME, "testAccessKey");
    data.put(AWS_SECRET_ACCESS_KEY_FIELDNAME, "SecretKey");

    ObjectMapper mapper = new ObjectMapper();
    ArrayNode regionLocations = mapper.createArrayNode();

    ObjectNode regionData = Json.newObject();
    regionData.put(CustomerConfigConsts.REGION_FIELDNAME, "eu");
    regionData.put(REGION_LOCATION_FIELDNAME, regionLocation);
    regionLocations.add(regionData);
    data.put(REGION_LOCATIONS_FIELDNAME, regionLocations);

    CustomerConfig config = createConfig(ConfigType.STORAGE, NAME_S3, data);
    if ((expectedMessage != null) && !expectedMessage.equals("")) {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(
              PlatformServiceException.class,
              "errorJson: {\""
                  + fieldFullName(REGION_LOCATION_FIELDNAME)
                  + "\":[\""
                  + expectedMessage
                  + "\"]}"));
    } else {
      allowedBuckets.add(regionLocation.substring(5).split("/", 2)[0]);
      customerConfigValidator.validateConfig(config);
    }
  }

  @Parameters({
    // BACKUP_LOCATION - incorrect -> disallowed.
    "https://abc, {}, Invalid gsUriPath format: https://abc, false",
    // Check empty GCP Credentials Json -> disallowed.
    "gs://test, {}, Invalid GCP Credential Json., true",
    // Valid case.
    "gs://test, {}, null, false",
  })
  @Test
  public void testValidateDataContent_Storage_GCSPreflightCheckValidator(
      String backupLocation,
      String credentialsJson,
      @Nullable String expectedMessage,
      boolean refuseCredentials) {
    ((LocalCustomerConfigValidator) customerConfigValidator).setRefuseKeys(refuseCredentials);
    ObjectNode data = Json.newObject();
    data.put(BACKUP_LOCATION_FIELDNAME, backupLocation);
    data.put(GCS_CREDENTIALS_JSON_FIELDNAME, credentialsJson);
    CustomerConfig config = createConfig(ConfigType.STORAGE, NAME_GCS, data);
    if ((expectedMessage != null) && !expectedMessage.equals("")) {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(
              PlatformServiceException.class,
              "errorJson: {\""
                  + fieldFullName(BACKUP_LOCATION_FIELDNAME)
                  + "\":[\""
                  + expectedMessage
                  + "\"]}"));
    } else {
      allowedBuckets.add(backupLocation);
      customerConfigValidator.validateConfig(config);
    }
  }

  @Parameters({
    // BACKUP_LOCATION - incorrect -> disallowed.
    "https://abc, Invalid gsUriPath format: https://abc",
    // Valid case.
    "gs://test, null",
    // Valid case.
    "gs://test2, null",
  })
  @Test
  public void testValidateDataContent_Storage_GCSPreflightCheckValidator_RegionLocation(
      String regionLocation, @Nullable String expectedMessage) {
    ObjectNode data = Json.newObject();
    data.put(BACKUP_LOCATION_FIELDNAME, "gs://test");
    allowedBuckets.add("test");
    data.put(GCS_CREDENTIALS_JSON_FIELDNAME, "{}");

    ObjectMapper mapper = new ObjectMapper();
    ArrayNode regionLocations = mapper.createArrayNode();

    ObjectNode regionData = Json.newObject();
    regionData.put(REGION_FIELDNAME, "eu");
    regionData.put(REGION_LOCATION_FIELDNAME, regionLocation);
    regionLocations.add(regionData);
    data.put(REGION_LOCATIONS_FIELDNAME, regionLocations);

    CustomerConfig config = createConfig(ConfigType.STORAGE, NAME_GCS, data);
    if ((expectedMessage != null) && !expectedMessage.equals("")) {
      assertThat(
          () -> customerConfigValidator.validateConfig(config),
          thrown(
              PlatformServiceException.class,
              "errorJson: {\""
                  + fieldFullName(REGION_LOCATION_FIELDNAME)
                  + "\":[\""
                  + expectedMessage
                  + "\"]}"));
    } else {
      allowedBuckets.add(regionLocation);
      customerConfigValidator.validateConfig(config);
    }
  }

  private CustomerConfig createConfig(ConfigType type, String name, ObjectNode data) {
    return new CustomerConfig()
        .setCustomerUUID(UUID.randomUUID())
        .setName(name)
        .setConfigName(name)
        .setType(type)
        .setData(data);
  }

  // @formatter:off
  /*
   * We are extending CustomerConfigValidator with some mocked behaviour.
   *
   *  - It allows to avoid direct cloud connections;
   *  - It allows to mark some buckets/URLs as existing and all others as wrong;
   *  - It allows to accept or to refuse keys/credentials.
   *
   */
  // @formatter:on
  private static class LocalCustomerConfigValidator extends CustomerConfigValidator {

    private final AmazonS3Client s3Client = mock(AmazonS3Client.class);

    private final Storage gcpStorage = mock(Storage.class);

    private boolean refuseKeys = false;

    public LocalCustomerConfigValidator(BeanValidator beanValidator, List<String> allowedBuckets) {
      super(beanValidator);

      when(s3Client.doesBucketExistV2(any(String.class)))
          .thenAnswer(invocation -> allowedBuckets.contains(invocation.getArguments()[0]));
      when(s3Client.listObjectsV2(any(String.class), any(String.class)))
          .thenAnswer(
              invocation -> {
                boolean allowedBucket = allowedBuckets.contains(invocation.getArguments()[0]);
                ListObjectsV2Result result = new ListObjectsV2Result();
                result.setKeyCount(allowedBucket ? 1 : 0);
                return result;
              });

      when(gcpStorage.list(any(String.class), any(BlobListOption.class), any(BlobListOption.class)))
          .thenAnswer(
              invocation -> {
                if (allowedBuckets.contains(invocation.getArguments()[0])) {
                  return new Page<Blob>() {
                    @Override
                    public boolean hasNextPage() {
                      return false;
                    }

                    @Override
                    public String getNextPageToken() {
                      return null;
                    }

                    @Override
                    public Page<Blob> getNextPage() {
                      return null;
                    }

                    @Override
                    public Iterable<Blob> iterateAll() {
                      return null;
                    }

                    @Override
                    public Iterable<Blob> getValues() {
                      return Collections.singleton(mock(Blob.class));
                    }
                  };
                }
                return mock(Page.class);
              });
    }

    @Override
    protected AmazonS3 create(JsonNode data) {
      if (refuseKeys) {
        throw new AmazonS3Exception(
            "The AWS Access Key Id you provided does not exist in our records.");
      }
      return s3Client;
    }

    @Override
    protected Storage createGcpStorage(String gcpCredentials)
        throws UnsupportedEncodingException, IOException {
      if (refuseKeys) {
        throw new IOException("Invalid GCP Credential Json.");
      }
      return gcpStorage;
    }

    public void setRefuseKeys(boolean value) {
      refuseKeys = value;
    }
  }
}
