package io.dataease.ee.permissions.role;

import io.dataease.api.permissions.role.api.RoleApi;
import io.dataease.api.permissions.role.dto.*;
import io.dataease.api.permissions.role.vo.RoleDetailVO;
import io.dataease.api.permissions.role.vo.RoleVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.user.EeUserManage;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RestController("roleServer")
@RequestMapping("/role")
public class RoleServer implements RoleApi {

    private final EeRoleManage roleManage;

    private final EeUserManage userManage;

    private final EeOrgManage orgManage;

    private final EeUserProvider userProvider;

    public RoleServer(EeRoleManage roleManage, EeUserManage userManage, EeOrgManage orgManage, EeUserProvider userProvider) {
        this.roleManage = roleManage;
        this.userManage = userManage;
        this.orgManage = orgManage;
        this.userProvider = userProvider;
    }

    public static RoleVO toVO(PerRole role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setRoot(Boolean.TRUE.equals(role.getRoot()));
        vo.setTypeCode(role.getTypeCode());
        vo.setReadonly(!Objects.equals(role.getTypeCode(), EeConstants.ROLE_TYPE_ADMIN));
        return vo;
    }

    private Long currentOid() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        return oid;
    }

    @Override
    public List<RoleVO> query() {
        Long oid = V3UserUtil.getOid();
        return oid == null ? new ArrayList<>() : roleManage.rolesOfOrg(oid).stream().map(RoleServer::toVO).toList();
    }

    @Override
    public Long create(RoleCreator creator) {
        return roleManage.create(currentOid(), creator.getName(), creator.getTypeCode(), creator.getDesc());
    }

    @Override
    public void edit(RoleEditor editor) {
        roleManage.edit(editor.getId(), editor.getName(), editor.getDesc());
    }

    @Override
    @Transactional
    public void mountUser(MountUserRequest request) {
        PerRole role = roleManage.get(request.getRid());
        if (role == null || CollectionUtils.isEmpty(request.getUids())) {
            return;
        }
        for (Long uid : request.getUids()) {
            userManage.require(uid);
            if (!orgManage.isMember(uid, role.getOid())) {
                DEException.throwException(Translator.get("ee_role_not_in_org"));
            }
            roleManage.mount(uid, role.getOid(), role.getId());
            userProvider.evict(uid);
        }
    }

    @Override
    public void unMountUser(UnmountUserRequest request) {
        PerRole role = roleManage.get(request.getRid());
        if (role == null) {
            return;
        }
        if (EeConstants.ADMIN_UID.equals(request.getUid()) && Boolean.TRUE.equals(role.getRoot())
                && Objects.equals(role.getTypeCode(), EeConstants.ROLE_TYPE_ADMIN)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        roleManage.unmount(request.getUid(), request.getRid());
        userProvider.evict(request.getUid());
    }

    @Override
    public List<RoleVO> optionForUser(RoleRequest request) {
        List<Long> oids = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(request.getOidList())) {
            oids.addAll(request.getOidList());
        } else if (request.getOid() != null) {
            oids.add(request.getOid());
        } else if (request.getUid() != null) {
            PerUser user = userManage.get(request.getUid());
            Long current = V3UserUtil.getOid();
            if (user != null && current != null && orgManage.isMember(user.getId(), current)
                    && (!V3UserUtil.isSysAdmin() || V3UserUtil.getProxy().isProxy())) {
                oids.add(current);
            } else if (user != null && orgManage.homeOid(user) != null) {
                oids.add(orgManage.homeOid(user));
            }
        } else if (V3UserUtil.getOid() != null) {
            oids.add(V3UserUtil.getOid());
        }
        if (!V3UserUtil.isSysAdmin()) {
            Long current = V3UserUtil.getOid();
            oids.removeIf(oid -> !Objects.equals(oid, current));
        }
        return roleManage.rolesOfOrgs(oids).stream().map(RoleServer::toVO).toList();
    }

    @Override
    public List<RoleVO> selectedForUser(RoleRequest request) {
        Long uid = request.getUid() == null ? V3UserUtil.getUid() : request.getUid();
        PerUser user = userManage.get(uid);
        if (user == null) {
            return new ArrayList<>();
        }
        // 当前组织内的角色（用户属于当前组织时），否则为主组织内的角色
        Long current = V3UserUtil.getOid();
        Long oid = current != null && orgManage.isMember(uid, current) ? current : orgManage.homeOid(user);
        List<Long> rids = oid == null ? List.of() : roleManage.roleIdsOfUser(uid, oid);
        return rids.stream().map(roleManage::get).filter(Objects::nonNull).map(RoleServer::toVO).toList();
    }

    @Override
    public RoleDetailVO detail(Long rid) {
        PerRole role = roleManage.get(rid);
        if (role == null) {
            return null;
        }
        if (!V3UserUtil.isSysAdmin() && !Objects.equals(role.getOid(), V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        RoleDetailVO vo = new RoleDetailVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setTypeCode(role.getTypeCode());
        vo.setDesc(role.getDescription());
        return vo;
    }

    @Override
    public void delete(Long rid) {
        List<Long> uids = roleManage.userIdsOfRole(rid);
        roleManage.delete(rid);
        uids.forEach(userProvider::evict);
    }

    @Override
    public List<RoleVO> queryWithOid(Long oid) {
        if (!V3UserUtil.isSysAdmin() && !Objects.equals(oid, V3UserUtil.getOid())) {
            return new ArrayList<>();
        }
        return roleManage.rolesOfOrg(oid).stream().map(RoleServer::toVO).toList();
    }
}
