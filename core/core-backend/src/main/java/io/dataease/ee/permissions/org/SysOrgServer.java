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
 * 组织成员管理（系统管理员/上级组织管理员）
 */
@RestController("sysOrgServer")
@RequestMapping("/sys/org")
public class SysOrgServer implements SysOrgApi {

    private final JPAQueryFactory queryFactory;

    private final EeUserManage userManage;

    private final EeRoleManage roleManage;

    private final EeEntityPermit entityPermit;

    public SysOrgServer(JPAQueryFactory queryFactory, EeUserManage userManage, EeRoleManage roleManage, EeEntityPermit entityPermit) {
        this.queryFactory = queryFactory;
        this.userManage = userManage;
        this.roleManage = roleManage;
        this.entityPermit = entityPermit;
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
        var query = queryFactory.selectFrom(q).where(q.defaultOid.eq(request.getOrgId()));
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

    @Override
    public List<SysOrgMemberVO> memberCandidates(KeywordRequest request) {
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q).where(q.defaultOid.isNull(), q.id.ne(EeConstants.ADMIN_UID));
        if (request != null && StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        return query.orderBy(q.createTime.desc()).fetch().stream().map(this::member).toList();
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

    @Override
    @Transactional
    public void addMembers(SysOrgMemberAddRequest request) {
        check(request.getOrgId());
        if (request.getUserIds() == null) {
            return;
        }
        for (Long uid : request.getUserIds()) {
            PerUser user = userManage.require(uid);
            if (EeConstants.ADMIN_UID.equals(uid)) {
                continue;
            }
            if (user.getDefaultOid() != null && !Objects.equals(user.getDefaultOid(), request.getOrgId())) {
                check(user.getDefaultOid());
            }
            userManage.moveToOrg(uid, request.getOrgId(), request.getRoleIds());
        }
    }

    @Override
    public void removeMember(Long userId, Long orgId) {
        check(orgId);
        if (EeConstants.ADMIN_UID.equals(userId)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        PerUser user = userManage.require(userId);
        if (!Objects.equals(user.getDefaultOid(), orgId)) {
            return;
        }
        userManage.moveToOrg(userId, null, null);
    }

    @Override
    public void switchRole(SysOrgMemberRoleSwitchRequest request) {
        check(request.getOrgId());
        PerUser user = userManage.require(request.getUserId());
        if (!Objects.equals(user.getDefaultOid(), request.getOrgId())) {
            DEException.throwException(Translator.get("ee_role_not_in_org"));
        }
        if (EeConstants.ADMIN_UID.equals(user.getId())) {
            PerRole adminRole = roleManage.builtinRole(request.getOrgId(), EeConstants.ROLE_TYPE_ADMIN);
            List<Long> rids = new ArrayList<>(request.getRoleIds() == null ? List.of() : request.getRoleIds());
            if (adminRole != null && !rids.contains(adminRole.getId())) {
                rids.add(adminRole.getId());
            }
            request.setRoleIds(rids);
        }
        userManage.moveToOrg(user.getId(), request.getOrgId(), request.getRoleIds());
    }
}
