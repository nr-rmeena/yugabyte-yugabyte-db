/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.TaskExecutor;
import com.yugabyte.yw.commissioner.tasks.params.CloudTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.cloud.CloudAccessKeySetup;
import com.yugabyte.yw.commissioner.tasks.subtasks.cloud.CloudImageBundleSetup;
import com.yugabyte.yw.commissioner.tasks.subtasks.cloud.CloudInitializer;
import com.yugabyte.yw.commissioner.tasks.subtasks.cloud.CloudRegionSetup;
import com.yugabyte.yw.controllers.handlers.ImageBundleHandler;
import com.yugabyte.yw.forms.ITaskParams;
import com.yugabyte.yw.models.ImageBundle;
import com.yugabyte.yw.models.Provider;
import java.util.Map;
import javax.inject.Inject;

public abstract class CloudTaskBase extends AbstractTaskBase {
  private Provider provider;
  protected Map<String, Object> regionMetadata;

  @Inject
  protected CloudTaskBase(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  protected CloudTaskParams taskParams() {
    return (CloudTaskParams) taskParams;
  }

  @Override
  public void initialize(ITaskParams params) {
    super.initialize(params);
    provider = Provider.get(taskParams().providerUUID);
    regionMetadata = configHelper.getRegionMetadata(Common.CloudType.valueOf(provider.getCode()));
  }

  public Provider getProvider() {
    return provider;
  }

  public Map<String, Object> getRegionMetadata() {
    return regionMetadata;
  }

  public TaskExecutor.SubTaskGroup createRegionSetupTask(
      String regionCode, CloudBootstrap.Params.PerRegionMetadata metadata, String destVpcId) {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("Create Region task");
    CloudRegionSetup.Params params = new CloudRegionSetup.Params();
    params.providerUUID = taskParams().providerUUID;
    params.regionCode = regionCode;
    params.metadata = metadata;
    params.destVpcId = destVpcId;

    CloudRegionSetup task = createTask(CloudRegionSetup.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public TaskExecutor.SubTaskGroup createAccessKeySetupTask(
      CloudBootstrap.Params taskParams, String regionCode) {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("Create Access Key");
    CloudAccessKeySetup.Params params = new CloudAccessKeySetup.Params();
    params.providerUUID = taskParams().providerUUID;
    params.regionCode = regionCode;
    params.keyPairName = taskParams.keyPairName;
    params.sshPrivateKeyContent = taskParams.sshPrivateKeyContent;
    params.sshUser = taskParams.sshUser;
    params.sshPort = taskParams.sshPort;
    params.airGapInstall = taskParams.airGapInstall;
    params.setUpChrony = taskParams.setUpChrony;
    params.ntpServers = taskParams.ntpServers;
    params.showSetUpChrony = taskParams.showSetUpChrony;
    params.skipProvisioning = taskParams.skipProvisioning;
    params.skipKeyValidateAndUpload = taskParams.skipKeyValidateAndUpload;
    CloudAccessKeySetup task = createTask(CloudAccessKeySetup.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public TaskExecutor.SubTaskGroup createInitializerTask() {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("Create Cloud initializer task");
    CloudInitializer.Params params = new CloudInitializer.Params();
    params.providerUUID = taskParams().providerUUID;
    CloudInitializer task = createTask(CloudInitializer.class);
    task.initialize(params);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public TaskExecutor.SubTaskGroup createImageBundleTask(Provider provider, ImageBundle bundle) {
    TaskExecutor.SubTaskGroup subTaskGroup = createSubTaskGroup("Create image bundle task");
    CloudImageBundleSetup.Params taskParams =
        ImageBundleHandler.getCloudImageBundleParams(provider, bundle);
    CloudImageBundleSetup task = createTask(CloudImageBundleSetup.class);
    task.initialize(taskParams);
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  @Override
  public String getName() {
    return super.getName() + "(" + taskParams().providerUUID + ")";
  }
}
