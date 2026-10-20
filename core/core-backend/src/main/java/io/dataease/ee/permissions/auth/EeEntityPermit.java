package io.dataease.ee.permissions.auth;

import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.repo.PerRoleRepository;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户、角色、组织等管理对象的管理权限
 */
@Component
public class EeEntityPermit {

    private final EePermissionManage permissionManage;

    private final EeOrgManage orgManage;

    private final PerRoleRepository perRoleRepository;

    public EeEntityPermit(EePermissionManage permissionManage, EeOrgManage orgManage, PerRoleRepository perRoleRepository) {
        this.permissionManage = permissionManage;
        this.orgManage = orgManage;
        this.perRoleRepository = perRoleRepository;
    }

    /**
     * 当前身份是否可以管理目标用户：系统管理员；或目标用户属于当前组织且当前用户为组织管理员
     */
    public boolean canManageUser(Long targetUid) {
        Long uid = V3UserUtil.getUid();
        if (permissionManage.isSysAdmin(uid)) {
            return true;
        }
        if (permissionManage.isSysAdmin(targetUid)) {
            return false;
        }
        Long oid = V3UserUtil.getOid();
        return permissionManage.isOrgAdmin(uid, oid) && orgManage.isMember(targetUid, oid);
    }

    /**
     * 当前身份是否可以管理角色：系统管理员；或角色属于当前组织且当前用户为组织管理员
     */
    public boolean canManageRole(Long rid) {
        Long uid = V3UserUtil.getUid();
        if (permissionManage.isSysAdmin(uid)) {
            return true;
        }
        PerRole role = rid == null ? null : perRoleRepository.findById(rid).orElse(null);
        if (role == null) {
            return false;
        }
        return role.getOid().equals(V3UserUtil.getOid()) && permissionManage.isOrgAdmin(uid, role.getOid());
    }

    /**
     * 当前身份是否可以管理组织：系统管理员；或为该组织（或其上级组织）的组织管理员
     */
    public boolean canManageOrg(Long oid) {
        Long uid = V3UserUtil.getUid();
        if (permissionManage.isSysAdmin(uid)) {
            return true;
        }
        if (oid == null) {
            return false;
        }
        if (permissionManage.isOrgAdmin(uid, oid)) {
            return true;
        }
        List<Long> ancestors = orgManage.ancestors(oid);
        return ancestors.stream().anyMatch(ancestor -> permissionManage.isOrgAdmin(uid, ancestor));
    }
}
