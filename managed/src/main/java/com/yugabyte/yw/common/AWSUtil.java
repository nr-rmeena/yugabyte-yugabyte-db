// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class AWSUtil {

  public static final String AWS_ACCESS_KEY_ID_FIELDNAME = "AWS_ACCESS_KEY_ID";
  public static final String AWS_SECRET_ACCESS_KEY_FIELDNAME = "AWS_SECRET_ACCESS_KEY";
  private static final String AWS_HOST_BASE_FIELDNAME = "AWS_HOST_BASE";
  private static final String AWS_PATH_STYLE_ACCESS = "PATH_STYLE_ACCESS";
  private static final String KEY_LOCATION_SUFFIX = Util.KEY_LOCATION_SUFFIX;

  // This method is a way to check if given S3 config can extract objects.
  public static Boolean canCredentialListObjects(JsonNode configData, List<String> locations) {
    for (String location : locations) {
      try {
        AmazonS3 s3Client = createS3Client(configData);
        String[] bucketSplit = getSplitLocationValue(location);
        String bucketName = bucketSplit.length > 0 ? bucketSplit[0] : "";
        String prefix = bucketSplit.length > 1 ? bucketSplit[1] : "";
        if (bucketSplit.length == 1) {
          Boolean doesBucketExist = s3Client.doesBucketExistV2(bucketName);
          if (!doesBucketExist) {
            log.error("No bucket exists with name {}", bucketName);
          }
        } else {
          ListObjectsV2Result result = s3Client.listObjectsV2(bucketName, prefix);
          if (result.getKeyCount() == 0) {
            log.error("No objects exists within bucket {}", bucketName);
          }
        }
      } catch (Exception e) {
        log.error(
            String.format(
                "Credential cannot list objects in the specified backup location %s", location),
            e);
        return false;
      }
    }
    return true;
  }

  public static void deleteKeyIfExists(JsonNode configData, String backupLocation)
      throws Exception {
    String[] splitLocation = getSplitLocationValue(backupLocation);
    String bucketName = splitLocation[0];
    String objectPrefix = splitLocation[1];
    String keyLocation =
        objectPrefix.substring(0, objectPrefix.lastIndexOf('/')) + KEY_LOCATION_SUFFIX;
    try {
      AmazonS3 s3Client = createS3Client(configData);
      ListObjectsV2Result listObjectsResult = s3Client.listObjectsV2(bucketName, keyLocation);
      if (listObjectsResult.getKeyCount() == 0) {
        log.info("Specified Location " + keyLocation + " does not contain objects");
        return;
      } else {
        log.debug("Retrieved blobs info for bucket " + bucketName + " with prefix " + keyLocation);
        retrieveAndDeleteObjects(listObjectsResult, bucketName, s3Client);
      }

    } catch (Exception e) {
      log.error("Error while deleting key object from bucket " + bucketName, e);
      throw e;
    }
  }

  private static String[] getSplitLocationValue(String location) {
    location = location.substring(5);
    String[] split = location.split("/", 2);
    return split;
  }

  public static AmazonS3 createS3Client(JsonNode data) {

    String key = data.get(AWS_ACCESS_KEY_ID_FIELDNAME).asText();
    String secret = data.get(AWS_SECRET_ACCESS_KEY_FIELDNAME).asText();
    Boolean isPathStyleAccess =
        data.has(AWS_PATH_STYLE_ACCESS) ? data.get(AWS_PATH_STYLE_ACCESS).asBoolean(false) : false;
    String endpoint =
        (data.get(AWS_HOST_BASE_FIELDNAME) != null
                && !StringUtils.isBlank(data.get(AWS_HOST_BASE_FIELDNAME).textValue()))
            ? data.get(AWS_HOST_BASE_FIELDNAME).textValue()
            : null;
    AWSCredentials credentials = new BasicAWSCredentials(key, secret);
    if (!isPathStyleAccess || endpoint == null) {
      AmazonS3Client client = new AmazonS3Client(credentials);
      if (endpoint != null) {
        client.setEndpoint(endpoint);
      }
      return client;
    }
    AWSCredentialsProvider creds = new AWSStaticCredentialsProvider(credentials);
    EndpointConfiguration endpointConfiguration = new EndpointConfiguration(endpoint, null);
    AmazonS3 client =
        AmazonS3Client.builder()
            .withCredentials(creds)
            .withForceGlobalBucketAccessEnabled(true)
            .withPathStyleAccessEnabled(true)
            .withEndpointConfiguration(endpointConfiguration)
            .build();
    return client;
  }

  public static void deleteStorage(JsonNode configData, List<String> backupLocations)
      throws Exception {
    for (String backupLocation : backupLocations) {
      try {
        AmazonS3 s3Client = createS3Client(configData);
        String[] splitLocation = getSplitLocationValue(backupLocation);
        String bucketName = splitLocation[0];
        String objectPrefix = splitLocation[1];
        String nextContinuationToken = null;
        do {
          ListObjectsV2Result listObjectsResult = s3Client.listObjectsV2(bucketName, objectPrefix);
          if (listObjectsResult.getKeyCount() == 0) {
            break;
          }
          nextContinuationToken = null;
          if (listObjectsResult.isTruncated()) {
            nextContinuationToken = listObjectsResult.getContinuationToken();
          }
          log.debug(
              "Retrieved blobs info for bucket " + bucketName + " with prefix " + objectPrefix);
          retrieveAndDeleteObjects(listObjectsResult, bucketName, s3Client);
        } while (nextContinuationToken != null);
      } catch (Exception e) {
        log.error(" Error in deleting objects at location " + backupLocation, e);
        throw e;
      }
    }
  }

  public static void retrieveAndDeleteObjects(
      ListObjectsV2Result listObjectsResult, String bucketName, AmazonS3 s3Client)
      throws Exception {
    List<S3ObjectSummary> objectSummary = listObjectsResult.getObjectSummaries();
    List<DeleteObjectsRequest.KeyVersion> objectKeys =
        objectSummary
            .parallelStream()
            .map(o -> new KeyVersion(o.getKey()))
            .collect(Collectors.toList());
    DeleteObjectsRequest deleteRequest =
        new DeleteObjectsRequest(bucketName).withKeys(objectKeys).withQuiet(false);
    s3Client.deleteObjects(deleteRequest);
  }
}
