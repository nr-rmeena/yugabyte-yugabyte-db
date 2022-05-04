// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.alerts.AlertDefinitionLabelsBuilder;
import com.yugabyte.yw.models.*;
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
      for (UUID universeUUID : Universe.getAllUUIDs(c)) {
        Optional<Universe> u = Universe.maybeGet(universeUUID);
        if (u.isPresent()) {
          Universe universe = u.get();
          for (AlertDefinitionTemplate template : AlertDefinitionTemplate.values()) {
            if (template.isCreateOnMigration()
                && (AlertDefinition.get(c.uuid, universeUUID, template.getName()) == null)
                && (universe.getUniverseDetails() != null)) {
              LOG.debug(
                  "Going to create alert definition for universe {} with name '{}'",
                  universeUUID,
                  template.getName());
              AlertDefinition.create(
                  c.uuid,
                  AlertDefinition.TargetType.Universe,
                  template.getName(),
                  template.buildTemplate(universe.getUniverseDetails().nodePrefix),
                  true,
                  AlertDefinitionLabelsBuilder.create().appendUniverse(universe).get());
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
