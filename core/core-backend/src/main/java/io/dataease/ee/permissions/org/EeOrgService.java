package io.dataease.ee.permissions.org;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.dao.auto.entity.QPerBusiResource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EeGrantManage;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.QPerOrg;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.repo.PerOrgRepository;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.ee.permissions.resource.EeResourceDeleter;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.ee.permissions.user.EeUserManage;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 组织的创建、修改、删除
 */
@Component
public class EeOrgService {

    private final JPAQueryFactory queryFactory;

    private final PerOrgRepository perOrgRepository;

    private final EeOrgManage orgManage;

    private final EeRoleManage roleManage;

    private final EeUserManage userManage;

    private final EeGrantManage grantManage;

    private final EeBusiResourceManage busiResourceManage;

    private final EeUserProvider userProvider;

    private final List<EeResourceDeleter> deleters;

    public EeOrgService(JPAQueryFactory queryFactory, PerOrgRepository perOrgRepository, EeOrgManage orgManage, EeRoleManage roleManage,
                        EeUserManage userManage, EeGrantManage grantManage, EeBusiResourceManage busiResourceManage,
                        EeUserProvider userProvider, List<EeResourceDeleter> deleters) {
        this.queryFactory = queryFactory;
        this.perOrgRepository = perOrgRepository;
        this.orgManage = orgManage;
        this.roleManage = roleManage;
        this.userManage = userManage;
        this.grantManage = grantManage;
        this.busiResourceManage = busiResourceManage;
        this.userProvider = userProvider;
        this.deleters = deleters;
    }

    @Transactional
    public Long create(Long pid, String name, List<Long> adminIds) {
        return create(null, pid, name, adminIds);
    }

    @Transactional
    public Long create(Long id, Long pid, String name, List<Long> adminIds) {
        String orgName = StringUtils.trim(name);
        Long parent = pid == null || pid < 0 ? 0L : pid;
        if (parent != 0L && !orgManage.exists(parent)) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        checkName(parent, orgName, null);
        PerOrg org = new PerOrg();
        org.setId(id == null ? IDUtils.snowID() : id);
        org.setPid(parent);
        org.setName(orgName);
        org.setRootPath(orgManage.buildRootPath(parent, org.getId()));
        org.setCreateTime(System.currentTimeMillis());
        org.setCreator(V3UserUtil.getUid());
        perOrgRepository.save(org);
        roleManage.createBuiltinRoles(org.getId(), null, null, null);
        if (CollectionUtils.isNotEmpty(adminIds)) {
            PerRole adminRole = roleManage.builtinRole(org.getId(), EeConstants.ROLE_TYPE_ADMIN);
            for (Long uid : adminIds) {
                if (EeConstants.ADMIN_UID.equals(uid)) {
                    continue;
                }
                userManage.moveToOrg(uid, org.getId(), List.of(adminRole.getId()));
            }
        }
        return org.getId();
    }

    @Transactional
    public void rename(Long id, String name) {
        PerOrg org = orgManage.get(id);
        if (org == null) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        String orgName = StringUtils.trim(name);
        checkName(org.getPid(), orgName, id);
        QPerOrg q = QPerOrg.perOrg;
        queryFactory.update(q).set(q.name, orgName).where(q.id.eq(id)).execute();
    }

    @Transactional
    public void delete(Long id, boolean delResource) {
        if (EeConstants.ROOT_ORG_ID.equals(id)) {
            DEException.throwException(Translator.get("ee_org_default_cannot_delete"));
        }
        PerOrg org = orgManage.get(id);
        if (org == null) {
            return;
        }
        QPerOrg q = QPerOrg.perOrg;
        if (queryFactory.selectOne().from(q).where(q.pid.eq(id)).fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_org_has_children"));
        }
        // 成员移出组织
        List<Long> members = orgManage.memberIds(id);
        QPerUser u = QPerUser.perUser;
        queryFactory.update(u).setNull(u.defaultOid).where(u.defaultOid.eq(id)).execute();
        members.forEach(userProvider::evict);
        roleManage.deleteByOrg(id);
        grantManage.deleteByOrg(id);
        handleResources(id, delResource);
        perOrgRepository.deleteById(id);
    }

    private void handleResources(Long oid, boolean delResource) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        List<PerBusiResource> resources = queryFactory.selectFrom(q).where(q.orgId.eq(oid)).fetch();
        if (resources.isEmpty()) {
            return;
        }
        if (!delResource) {
            // 游离资源归入默认组织，由系统管理员处理
            busiResourceManage.changeOrg(resources.stream().map(PerBusiResource::getId).toList(), EeConstants.ROOT_ORG_ID);
            return;
        }
        Set<Long> ids = new HashSet<>();
        resources.forEach(item -> ids.add(item.getId()));
        for (PerBusiResource resource : resources) {
            if (resource.getPid() != null && ids.contains(resource.getPid())) {
                continue;
            }
            deleters.stream().filter(item -> item.rtId() == resource.getRtId()).findFirst().ifPresent(deleter -> {
                try {
                    deleter.delete(resource.getId());
                } catch (Exception e) {
                    LogUtil.error("delete resource " + resource.getId() + " failed: " + e.getMessage(), e);
                }
            });
        }
        busiResourceManage.deleteByOrg(oid);
    }

    private void checkName(Long pid, String name, Long excludeId) {
        if (StringUtils.isBlank(name)) {
            DEException.throwException(Translator.get("ee_org_name_exists"));
        }
        QPerOrg q = QPerOrg.perOrg;
        var query = queryFactory.selectOne().from(q).where(q.pid.eq(pid), q.name.eq(name));
        if (excludeId != null) {
            query.where(q.id.ne(excludeId));
        }
        if (query.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_org_name_exists"));
        }
    }
}
