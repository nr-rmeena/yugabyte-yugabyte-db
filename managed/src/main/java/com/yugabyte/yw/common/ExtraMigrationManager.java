// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.alerts.AlertDefinitionLabelsBuilder;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

import static com.yugabyte.yw.commissioner.Common.CloudType.onprem;

/*
 * This is a manager to hold injected resources needed for extra migrations.
 */
@Singleton
public class ExtraMigrationManager extends DevopsBase {

  public static final Logger LOG = LoggerFactory.getLogger(ExtraMigrationManager.class);

  @Inject TemplateManager templateManager;

  @Inject AlertDefinitionService alertDefinitionService;

  @Inject RuntimeConfigFactory runtimeConfigFactory;

  @Override
  protected String getCommandType() {
    return "";
  }

  private void recreateProvisionScripts() {
    for (AccessKey accessKey : AccessKey.getAll()) {
      Provider p = Provider.get(accessKey.getProviderUUID());
      if (p != null && p.code.equals(onprem.name())) {
        AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
        templateManager.createProvisionTemplate(
            accessKey,
            keyInfo.airGapInstall,
            keyInfo.passwordlessSudoAccess,
            keyInfo.installNodeExporter,
            keyInfo.nodeExporterPort,
            keyInfo.nodeExporterUser);
      }
    }
  }

  public void V52__Update_Access_Key_Create_Extra_Migration() {
    recreateProvisionScripts();
  }

  public void R__Recreate_Provision_Script_Extra_Migrations() {
    recreateProvisionScripts();
  }

  private void recreateMissedAlertDefinitions() {
    LOG.info("recreateMissedAlertDefinitions");
    for (Customer c : Customer.getAll()) {
      Config customerConfig = runtimeConfigFactory.forCustomer(c);
      for (UUID universeUUID : Universe.getAllUUIDs(c)) {
        Optional<Universe> u = Universe.maybeGet(universeUUID);
        if (u.isPresent()) {
          Universe universe = u.get();
          for (AlertDefinitionTemplate template : AlertDefinitionTemplate.values()) {
            boolean definitionMissing =
                alertDefinitionService
                    .list(
                        AlertDefinitionFilter.builder()
                            .customerUuid(c.uuid)
                            .name(template.getName())
                            .label(KnownAlertLabels.UNIVERSE_UUID, universeUUID.toString())
                            .build())
                    .isEmpty();
            if (template.isCreateOnMigration()
                && definitionMissing
                && (universe.getUniverseDetails() != null)) {
              LOG.debug(
                  "Going to create alert definition for universe {} with name '{}'",
                  universeUUID,
                  template.getName());
              AlertDefinition alertDefinition =
                  new AlertDefinition()
                      .setCustomerUUID(c.getUuid())
                      .setName(template.getName())
                      .setQuery(template.buildTemplate(universe.getUniverseDetails().nodePrefix))
                      .setQueryThreshold(
                          customerConfig.getDouble(template.getDefaultThresholdParamName()))
                      .setLabels(
                          AlertDefinitionLabelsBuilder.create().appendTarget(universe).get());
              alertDefinitionService.create(alertDefinition);
            }
          }
        } else {
          LOG.info(
              "Unable to create alert definitions for universe {} as it is not found.",
              universeUUID);
        }
      }
    }
  }

  public void V68__Create_New_Alert_Definitions_Extra_Migration() {
    recreateMissedAlertDefinitions();
  }
}
