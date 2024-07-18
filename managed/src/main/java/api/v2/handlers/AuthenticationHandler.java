// Copyright (c) Yugabyte, Inc.

package api.v2.handlers;

import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.NOT_FOUND;

import api.v2.mappers.RoleResourceDefinitionMapper;
import api.v2.models.GroupMappingSpec;
import api.v2.models.GroupMappingSpec.TypeEnum;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.GlobalConfKeys;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.common.rbac.RoleBindingUtil;
import com.yugabyte.yw.common.rbac.RoleResourceDefinition;
import com.yugabyte.yw.controllers.TokenAuthenticator;
import com.yugabyte.yw.models.GroupMappingInfo;
import com.yugabyte.yw.models.GroupMappingInfo.GroupType;
import com.yugabyte.yw.models.rbac.Role;
import com.yugabyte.yw.models.rbac.RoleBinding;
import com.yugabyte.yw.models.rbac.RoleBinding.RoleBindingType;
import io.ebean.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import play.mvc.Http;

@Slf4j
@Singleton
public class AuthenticationHandler {

  @Inject RuntimeConfGetter confGetter;
  @Inject TokenAuthenticator tokenAuthenticator;
  @Inject RoleBindingUtil roleBindingUtil;

  public List<GroupMappingSpec> listMappings(UUID cUUID) throws Exception {

    checkRuntimeConfig();

    List<GroupMappingInfo> groupInfoList =
        GroupMappingInfo.find.query().where().eq("customer_uuid", cUUID).findList();
    List<GroupMappingSpec> specList = new ArrayList<GroupMappingSpec>();
    for (GroupMappingInfo info : groupInfoList) {
      GroupMappingSpec spec =
          new GroupMappingSpec()
              .groupIdentifier(info.getIdentifier())
              .type(TypeEnum.valueOf(info.getType().toString()))
              .uuid(info.getGroupUUID());

      List<RoleResourceDefinition> roleResourceDefinitions = new ArrayList<>();
      if (confGetter.getGlobalConf(GlobalConfKeys.useNewRbacAuthz)) {
        // fetch all role rolebindings for the current group
        List<RoleBinding> roleBindingList = RoleBinding.getAll(info.getGroupUUID());
        for (RoleBinding rb : roleBindingList) {
          RoleResourceDefinition roleResourceDefinition =
              new RoleResourceDefinition(rb.getRole().getRoleUUID(), rb.getResourceGroup());
          roleResourceDefinitions.add(roleResourceDefinition);
        }
      } else {
        // No role bindings present if RBAC is off.
        RoleResourceDefinition rrd = new RoleResourceDefinition();
        rrd.setRoleUUID(info.getRoleUUID());
        roleResourceDefinitions.add(rrd);
      }
      spec.setRoleResourceDefinitions(
          RoleResourceDefinitionMapper.INSTANCE.toV2RoleResourceDefinitionList(
              roleResourceDefinitions));
      specList.add(spec);
    }
    return specList;
  }

  @Transactional
  public void updateGroupMappings(
      Http.Request request, UUID cUUID, List<GroupMappingSpec> groupMappingSpec) {
    boolean isSuperAdmin = tokenAuthenticator.superAdminAuthentication(request);
    if (!isSuperAdmin) {
      throw new PlatformServiceException(BAD_REQUEST, "Only SuperAdmin can create group mappings!");
    }

    checkRuntimeConfig();

    for (GroupMappingSpec mapping : groupMappingSpec) {
      GroupMappingInfo mappingInfo =
          GroupMappingInfo.find
              .query()
              .where()
              .eq("customer_uuid", cUUID)
              .ieq("identifier", mapping.getGroupIdentifier())
              .findOne();

      if (mappingInfo == null) {
        // new entry for new group
        log.info("Adding new group mapping entry for group: " + mapping.getGroupIdentifier());
        mappingInfo =
            GroupMappingInfo.create(
                cUUID,
                mapping.getGroupIdentifier(),
                GroupType.valueOf(mapping.getType().toString()));
      } else {
        // clear role bindings for existing group
        clearRoleBindings(mappingInfo);
      }

      List<RoleResourceDefinition> roleResourceDefinitions =
          RoleResourceDefinitionMapper.INSTANCE.toV1RoleResourceDefinitionList(
              mapping.getRoleResourceDefinitions());

      roleBindingUtil.validateRoles(cUUID, roleResourceDefinitions);
      roleBindingUtil.validateResourceGroups(cUUID, roleResourceDefinitions);

      if (confGetter.getGlobalConf(GlobalConfKeys.useNewRbacAuthz)) {
        // Add role bindings if rbac is on.
        for (RoleResourceDefinition rrd : roleResourceDefinitions) {
          Role rbacRole = Role.getOrBadRequest(cUUID, rrd.getRoleUUID());
          RoleBinding.create(mappingInfo, RoleBindingType.Custom, rbacRole, rrd.getResourceGroup());
        }
        // This role will be ignored when rbac is on.
        mappingInfo.setRoleUUID(Role.get(cUUID, "ConnectOnly").getRoleUUID());
      } else {
        validate(roleResourceDefinitions);
        mappingInfo.setRoleUUID(roleResourceDefinitions.get(0).getRoleUUID());
      }
      mappingInfo.save();
    }
  }

  @Transactional
  public void deleteGroupMappings(Http.Request request, UUID cUUID, UUID gUUID) {
    boolean isSuperAdmin = tokenAuthenticator.superAdminAuthentication(request);
    if (!isSuperAdmin) {
      throw new PlatformServiceException(BAD_REQUEST, "Only SuperAdmin can delete group mappings!");
    }

    checkRuntimeConfig();

    GroupMappingInfo entity =
        GroupMappingInfo.find
            .query()
            .where()
            .eq("customer_uuid", cUUID)
            .eq("uuid", gUUID)
            .findOne();
    if (entity == null) {
      throw new PlatformServiceException(NOT_FOUND, "No group mapping found with uuid: " + gUUID);
    }

    // Delete all role bindings
    clearRoleBindings(entity);
    log.info("Deleting Group Mapping with name: " + entity.getIdentifier());
    entity.delete();
  }

  private void clearRoleBindings(GroupMappingInfo mappingInfo) {
    log.info("Clearing role bindings for group: " + mappingInfo.getIdentifier());
    List<RoleBinding> list = RoleBinding.getAll(mappingInfo.getGroupUUID());
    list.forEach(rb -> rb.delete());
  }

  private void checkRuntimeConfig() {
    if (!confGetter.getGlobalConf(GlobalConfKeys.groupMappingRbac)) {
      throw new PlatformServiceException(
          BAD_REQUEST, "yb.security.group_mapping_rbac_support runtime config is disabled!");
    }
  }

  /**
   * Validation to make sure only a single system role is present when RBAC is off.
   *
   * @param cUUID
   * @param rrdList
   */
  private void validate(List<RoleResourceDefinition> rrdList) {
    if (rrdList.size() != 1) {
      throw new PlatformServiceException(BAD_REQUEST, "Need to specify a single system role!");
    }
  }
}
