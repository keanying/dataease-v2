package io.dataease.ee.permissions.org;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.org.api.SysOrgApi;
import io.dataease.api.permissions.org.dto.*;
import io.dataease.api.permissions.org.vo.SysOrgMemberVO;
import io.dataease.api.permissions.org.vo.SysOrgRoleOptionVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EeEntityPermit;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.ee.permissions.user.EeUserManage;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.KeywordRequest;
import io.dataease.result.PageResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 组织成员管理（系统管理员/上级组织管理员）。用户可同时属于多个组织（per_user_org）。
 */
@RestController("sysOrgServer")
@RequestMapping("/sys/org")
public class SysOrgServer implements SysOrgApi {

    private final JPAQueryFactory queryFactory;

    private final EeUserManage userManage;

    private final EeRoleManage roleManage;

    private final EeEntityPermit entityPermit;

    private final EeOrgManage orgManage;

    private final EeUserProvider userProvider;

    public SysOrgServer(JPAQueryFactory queryFactory, EeUserManage userManage, EeRoleManage roleManage, EeEntityPermit entityPermit,
                        EeOrgManage orgManage, EeUserProvider userProvider) {
        this.queryFactory = queryFactory;
        this.userManage = userManage;
        this.roleManage = roleManage;
        this.entityPermit = entityPermit;
        this.orgManage = orgManage;
        this.userProvider = userProvider;
    }

    private void check(Long orgId) {
        if (orgId == null || !entityPermit.canManageOrg(orgId)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    @Override
    public PageResult<SysOrgMemberVO> memberPage(SysOrgMemberRequest request) {
        check(request.getOrgId());
        int page = (int) Math.max(1, request.getCurrentPage() == null ? 1 : request.getCurrentPage());
        int size = (int) Math.max(1, request.getPageSize() == null ? 10 : request.getPageSize());
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q).where(orgManage.memberOf(q.id, request.getOrgId()));
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        query.orderBy(q.createTime.desc());
        long total = query.clone().fetchCount();
        List<PerUser> users = query.offset((long) (page - 1) * size).limit(size).fetch();
        Map<Long, List<PerRole>> roles = roleManage.rolesOfUsers(users.stream().map(PerUser::getId).toList());
        List<SysOrgMemberVO> records = new ArrayList<>();
        for (PerUser user : users) {
            SysOrgMemberVO vo = member(user);
            vo.setRoles(roles.getOrDefault(user.getId(), Collections.emptyList()).stream()
                    .filter(role -> Objects.equals(role.getOid(), request.getOrgId()))
                    .map(role -> {
                        SysOrgMemberVO.RoleItem item = new SysOrgMemberVO.RoleItem();
                        item.setRoleId(role.getId());
                        item.setRoleName(role.getName());
                        return item;
                    }).toList());
            records.add(vo);
        }
        return new PageResult<>(records, total, PageRequest.of(page - 1, size));
    }

    private SysOrgMemberVO member(PerUser user) {
        SysOrgMemberVO vo = new SysOrgMemberVO();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setEmail(user.getEmail());
        vo.setEnable(user.getEnable());
        vo.setRoles(new ArrayList<>());
        return vo;
    }

    /**
     * 可添加为成员的用户：系统管理员可选全部用户；上级组织管理员可选其可管理的组织内的用户。
     * 目标组织通过查询参数 orgId 传入（请求体只有 keyword），传入时排除已是该组织成员的用户；
     * 未传入时返回全部候选，添加时已是成员的用户只更新角色。
     */
    @Override
    public List<SysOrgMemberVO> memberCandidates(KeywordRequest request) {
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q).where(q.id.ne(EeConstants.ADMIN_UID));
        Long orgId = targetOrgParam();
        if (orgId != null) {
            check(orgId);
            query.where(orgManage.memberOf(q.id, orgId).not());
        }
        if (!sysAdmin()) {
            Long oid = V3UserUtil.getOid();
            if (oid == null || !entityPermit.canManageOrg(oid)) {
                return new ArrayList<>();
            }
            query.where(orgManage.memberOfAny(q.id, orgManage.selfAndChildren(oid)));
        }
        if (request != null && StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        return query.orderBy(q.createTime.desc()).fetch().stream().map(this::member).toList();
    }

    private boolean sysAdmin() {
        return V3UserUtil.isSysAdmin() && !V3UserUtil.getProxy().isProxy();
    }

    private Long targetOrgParam() {
        HttpServletRequest http = ServletUtils.request();
        String value = http == null ? null : http.getParameter("orgId");
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<SysOrgRoleOptionVO> memberRoleOptions(SysOrgRoleOptionRequest request) {
        check(request.getOrgId());
        return roleManage.rolesOfOrg(request.getOrgId()).stream().map(role -> {
            SysOrgRoleOptionVO vo = new SysOrgRoleOptionVO();
            vo.setId(role.getId());
            vo.setName(role.getName());
            return vo;
        }).toList();
    }

    /**
     * 添加成员：加入目标组织并授予所选角色，不移出用户原有的组织；已是成员时替换其在该组织内的角色
     */
    @Override
    @Transactional
    public void addMembers(SysOrgMemberAddRequest request) {
        check(request.getOrgId());
        if (request.getUserIds() == null) {
            return;
        }
        for (Long uid : request.getUserIds()) {
            userManage.require(uid);
            if (EeConstants.ADMIN_UID.equals(uid)) {
                continue;
            }
            if (!sysAdmin() && !orgManage.isMember(uid, request.getOrgId())) {
                // 非系统管理员只能添加自己可管理的组织内的用户
                List<Long> oids = orgManage.memberOids(uid);
                if (!oids.isEmpty() && oids.stream().noneMatch(entityPermit::canManageOrg)) {
                    DEException.throwException(Translator.get("ee_no_permission"));
                }
            }
            userManage.joinOrg(uid, request.getOrgId(), request.getRoleIds());
        }
    }

    /**
     * 移出成员：只删除该组织的成员关系、组织内角色与授权，用户在其他组织的成员关系不变
     */
    @Override
    @Transactional
    public void removeMember(Long userId, Long orgId) {
        check(orgId);
        if (EeConstants.ADMIN_UID.equals(userId)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        userManage.require(userId);
        if (!orgManage.isMember(userId, orgId)) {
            return;
        }
        userManage.leaveOrg(userId, orgId);
    }

    @Override
    @Transactional
    public void switchRole(SysOrgMemberRoleSwitchRequest request) {
        check(request.getOrgId());
        PerUser user = userManage.require(request.getUserId());
        if (!orgManage.isMember(user.getId(), request.getOrgId())) {
            DEException.throwException(Translator.get("ee_role_not_in_org"));
        }
        List<Long> rids = new ArrayList<>(request.getRoleIds() == null ? List.of() : request.getRoleIds());
        if (EeConstants.ADMIN_UID.equals(user.getId())) {
            PerRole adminRole = roleManage.builtinRole(request.getOrgId(), EeConstants.ROLE_TYPE_ADMIN);
            if (adminRole != null && !rids.contains(adminRole.getId())) {
                rids.add(adminRole.getId());
            }
        }
        roleManage.replaceRoles(user.getId(), request.getOrgId(), rids);
        userProvider.evict(user.getId());
    }
}
