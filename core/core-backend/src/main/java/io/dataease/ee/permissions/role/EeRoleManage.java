package io.dataease.ee.permissions.role;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EeGrantManage;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUserRole;
import io.dataease.ee.permissions.dao.entity.QPerRole;
import io.dataease.ee.permissions.dao.entity.QPerUserRole;
import io.dataease.ee.permissions.dao.repo.PerRoleRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRoleRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 角色与用户角色关系
 */
@Component
public class EeRoleManage {

    private final JPAQueryFactory queryFactory;

    private final PerRoleRepository perRoleRepository;

    private final PerUserRoleRepository perUserRoleRepository;

    private final EeGrantManage grantManage;

    public EeRoleManage(JPAQueryFactory queryFactory, PerRoleRepository perRoleRepository, PerUserRoleRepository perUserRoleRepository,
                        EeGrantManage grantManage) {
        this.queryFactory = queryFactory;
        this.perRoleRepository = perRoleRepository;
        this.perUserRoleRepository = perUserRoleRepository;
        this.grantManage = grantManage;
    }

    public PerRole get(Long rid) {
        return rid == null ? null : perRoleRepository.findById(rid).orElse(null);
    }

    /**
     * 组织内全部角色：内置角色在前（管理员、分析师、普通用户），自定义角色按创建时间
     */
    public List<PerRole> rolesOfOrg(Long oid) {
        if (oid == null) {
            return Collections.emptyList();
        }
        QPerRole q = QPerRole.perRole;
        List<PerRole> roles = new ArrayList<>(queryFactory.selectFrom(q).where(q.oid.eq(oid)).fetch());
        roles.sort(Comparator.comparing((PerRole role) -> !Boolean.TRUE.equals(role.getRoot()))
                .thenComparing(role -> Boolean.TRUE.equals(role.getRoot()) ? -role.getTypeCode() : 0)
                .thenComparing(PerRole::getCreateTime));
        return roles;
    }

    public List<PerRole> rolesOfOrgs(Collection<Long> oids) {
        List<PerRole> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(oids)) {
            return result;
        }
        for (Long oid : new LinkedHashSet<>(oids)) {
            result.addAll(rolesOfOrg(oid));
        }
        return result;
    }

    public PerRole builtinRole(Long oid, int typeCode) {
        QPerRole q = QPerRole.perRole;
        return queryFactory.selectFrom(q).where(q.oid.eq(oid), q.root.isTrue(), q.typeCode.eq(typeCode)).fetchFirst();
    }

    /**
     * 为组织创建内置角色：组织管理员、数据分析师、普通用户
     */
    @Transactional
    public void createBuiltinRoles(Long oid, Long adminRid, Long analystRid, Long staffRid) {
        createBuiltin(oid, adminRid, "ee_role_admin", EeConstants.ROLE_TYPE_ADMIN);
        createBuiltin(oid, analystRid, "ee_role_analyst", EeConstants.ROLE_TYPE_ANALYST);
        createBuiltin(oid, staffRid, "ee_role_staff", EeConstants.ROLE_TYPE_STAFF);
    }

    private void createBuiltin(Long oid, Long rid, String nameKey, int typeCode) {
        if (builtinRole(oid, typeCode) != null) {
            return;
        }
        PerRole role = new PerRole();
        role.setId(rid == null ? IDUtils.snowID() : rid);
        role.setOid(oid);
        role.setName(Translator.get(nameKey));
        role.setTypeCode(typeCode);
        role.setRoot(true);
        role.setReadonly(typeCode != EeConstants.ROLE_TYPE_ADMIN);
        role.setCreateTime(System.currentTimeMillis());
        perRoleRepository.save(role);
    }

    @Transactional
    public Long create(Long oid, String name, Integer typeCode, String desc) {
        checkName(oid, name, null);
        PerRole role = new PerRole();
        role.setId(IDUtils.snowID());
        role.setOid(oid);
        role.setName(name.trim());
        int type = typeCode == null ? EeConstants.ROLE_TYPE_STAFF : typeCode;
        if (type != EeConstants.ROLE_TYPE_STAFF && type != EeConstants.ROLE_TYPE_ANALYST && type != EeConstants.ROLE_TYPE_ADMIN) {
            type = EeConstants.ROLE_TYPE_STAFF;
        }
        role.setTypeCode(type);
        role.setRoot(false);
        role.setReadonly(type != EeConstants.ROLE_TYPE_ADMIN);
        role.setDescription(desc);
        role.setCreateTime(System.currentTimeMillis());
        perRoleRepository.save(role);
        return role.getId();
    }

    @Transactional
    public void edit(Long rid, String name, String desc) {
        PerRole role = get(rid);
        if (role == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (Boolean.TRUE.equals(role.getRoot())) {
            DEException.throwException(Translator.get("ee_role_root_readonly"));
        }
        checkName(role.getOid(), name, rid);
        QPerRole q = QPerRole.perRole;
        var update = queryFactory.update(q).set(q.name, name.trim()).where(q.id.eq(rid));
        if (desc == null) {
            update.setNull(q.description);
        } else {
            update.set(q.description, desc);
        }
        update.execute();
    }

    @Transactional
    public void delete(Long rid) {
        PerRole role = get(rid);
        if (role == null) {
            return;
        }
        if (Boolean.TRUE.equals(role.getRoot())) {
            DEException.throwException(Translator.get("ee_role_root_readonly"));
        }
        QPerUserRole ur = QPerUserRole.perUserRole;
        queryFactory.delete(ur).where(ur.rid.eq(rid)).execute();
        grantManage.deleteBySubject(EeConstants.SUBJECT_ROLE, rid);
        perRoleRepository.deleteById(rid);
    }

    @Transactional
    public void deleteByOrg(Long oid) {
        QPerRole q = QPerRole.perRole;
        List<Long> rids = queryFactory.select(q.id).from(q).where(q.oid.eq(oid)).fetch();
        QPerUserRole ur = QPerUserRole.perUserRole;
        queryFactory.delete(ur).where(ur.oid.eq(oid)).execute();
        for (Long rid : rids) {
            grantManage.deleteBySubject(EeConstants.SUBJECT_ROLE, rid);
        }
        queryFactory.delete(q).where(q.oid.eq(oid)).execute();
    }

    private void checkName(Long oid, String name, Long excludeId) {
        if (StringUtils.isBlank(name)) {
            DEException.throwException(Translator.get("ee_role_name_exists"));
        }
        QPerRole q = QPerRole.perRole;
        var query = queryFactory.selectOne().from(q).where(q.oid.eq(oid), q.name.eq(name.trim()));
        if (excludeId != null) {
            query.where(q.id.ne(excludeId));
        }
        if (query.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_role_name_exists"));
        }
    }

    /**
     * 用户在组织内的角色ID
     */
    public List<Long> roleIdsOfUser(Long uid, Long oid) {
        QPerUserRole ur = QPerUserRole.perUserRole;
        var query = queryFactory.select(ur.rid).from(ur).where(ur.uid.eq(uid));
        if (oid != null) {
            query.where(ur.oid.eq(oid));
        }
        return query.fetch();
    }

    /**
     * 批量查询用户角色：uid -> 角色
     */
    public Map<Long, List<PerRole>> rolesOfUsers(Collection<Long> uids) {
        Map<Long, List<PerRole>> result = new HashMap<>();
        if (CollectionUtils.isEmpty(uids)) {
            return result;
        }
        QPerUserRole ur = QPerUserRole.perUserRole;
        QPerRole r = QPerRole.perRole;
        var tuples = queryFactory.select(ur.uid, r).from(ur).innerJoin(r).on(ur.rid.eq(r.id)).where(ur.uid.in(uids)).fetch();
        for (var tuple : tuples) {
            result.computeIfAbsent(tuple.get(ur.uid), key -> new ArrayList<>()).add(tuple.get(r));
        }
        return result;
    }

    public List<Long> userIdsOfRole(Long rid) {
        QPerUserRole ur = QPerUserRole.perUserRole;
        return queryFactory.select(ur.uid).from(ur).where(ur.rid.eq(rid)).fetch();
    }

    /**
     * 替换用户在组织内的角色（角色必须属于该组织）
     */
    @Transactional
    public void replaceRoles(Long uid, Long oid, Collection<Long> roleIds) {
        QPerUserRole ur = QPerUserRole.perUserRole;
        queryFactory.delete(ur).where(ur.uid.eq(uid)).execute();
        if (oid == null || CollectionUtils.isEmpty(roleIds)) {
            return;
        }
        for (Long rid : new LinkedHashSet<>(roleIds)) {
            mount(uid, oid, rid);
        }
    }

    @Transactional
    public void mount(Long uid, Long oid, Long rid) {
        PerRole role = get(rid);
        if (role == null || !role.getOid().equals(oid)) {
            DEException.throwException(Translator.get("ee_role_not_in_org"));
        }
        QPerUserRole ur = QPerUserRole.perUserRole;
        if (queryFactory.selectOne().from(ur).where(ur.uid.eq(uid), ur.rid.eq(rid)).fetchFirst() != null) {
            return;
        }
        PerUserRole relation = new PerUserRole();
        relation.setId(IDUtils.snowID());
        relation.setUid(uid);
        relation.setRid(rid);
        relation.setOid(oid);
        relation.setCreateTime(System.currentTimeMillis());
        perUserRoleRepository.save(relation);
    }

    @Transactional
    public void unmount(Long uid, Long rid) {
        QPerUserRole ur = QPerUserRole.perUserRole;
        queryFactory.delete(ur).where(ur.uid.eq(uid), ur.rid.eq(rid)).execute();
    }

    @Transactional
    public void removeUser(Long uid) {
        QPerUserRole ur = QPerUserRole.perUserRole;
        queryFactory.delete(ur).where(ur.uid.eq(uid)).execute();
    }
}
