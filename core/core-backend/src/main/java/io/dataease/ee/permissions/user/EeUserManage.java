package io.dataease.ee.permissions.user;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.user.dto.UserCreator;
import io.dataease.api.permissions.user.dto.UserEditor;
import io.dataease.api.permissions.user.dto.UserGridRequest;
import io.dataease.ee.auth.PasswordUtils;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EeGrantManage;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.entity.QPerUserPlatform;
import io.dataease.ee.permissions.dao.entity.QPerUserRole;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户领域逻辑
 */
@Component
public class EeUserManage {

    private final JPAQueryFactory queryFactory;

    private final PerUserRepository perUserRepository;

    private final EeRoleManage roleManage;

    private final EeOrgManage orgManage;

    private final EeGrantManage grantManage;

    private final EeUserProvider userProvider;

    private final ObjectProvider<EeUserVariableSupport> variableSupport;

    @Value("${dataease.default-pwd:" + EeConstants.DEFAULT_PWD + "}")
    private String defaultPwd;

    public EeUserManage(JPAQueryFactory queryFactory, PerUserRepository perUserRepository, EeRoleManage roleManage, EeOrgManage orgManage,
                        EeGrantManage grantManage, EeUserProvider userProvider, ObjectProvider<EeUserVariableSupport> variableSupport) {
        this.queryFactory = queryFactory;
        this.perUserRepository = perUserRepository;
        this.roleManage = roleManage;
        this.orgManage = orgManage;
        this.grantManage = grantManage;
        this.userProvider = userProvider;
        this.variableSupport = variableSupport;
    }

    public String defaultPwd() {
        return defaultPwd;
    }

    public PerUser get(Long uid) {
        return uid == null ? null : perUserRepository.findById(uid).orElse(null);
    }

    public PerUser require(Long uid) {
        PerUser user = get(uid);
        if (user == null) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        return user;
    }

    public PerUser findByAccount(String account) {
        QPerUser q = QPerUser.perUser;
        return queryFactory.selectFrom(q).where(q.account.eq(account)).fetchFirst();
    }

    public EeUserVariableSupport variables() {
        EeUserVariableSupport support = variableSupport.getIfAvailable();
        return support == null ? new EeUserVariableSupport() {
        } : support;
    }

    /**
     * 新建用户
     *
     * @param rawPwd 明文密码，为空时使用默认密码
     */
    @Transactional
    public Long create(UserCreator creator, int origin, String rawPwd) {
        String account = StringUtils.trim(creator.getAccount());
        if (StringUtils.isBlank(account)) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        if (findByAccount(account) != null) {
            DEException.throwException(Translator.get("ee_account_exists"));
        }
        checkEmail(creator.getEmail(), null);
        Long oid = creator.getDefaultOid();
        if (oid != null && !orgManage.exists(oid)) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        long now = System.currentTimeMillis();
        PerUser user = new PerUser();
        user.setId(IDUtils.snowID());
        user.setAccount(account);
        user.setName(StringUtils.defaultIfBlank(StringUtils.trim(creator.getName()), account));
        user.setEmail(StringUtils.trimToNull(creator.getEmail()));
        user.setPhonePrefix(creator.getPhonePrefix());
        user.setPhone(StringUtils.trimToNull(creator.getPhone()));
        user.setEnable(creator.getEnable() == null || creator.getEnable());
        user.setOrigin(origin);
        user.setDefaultOid(oid);
        user.setMfaEnable(Boolean.TRUE.equals(creator.getMfaEnable()));
        user.setPwd(PasswordUtils.hash(StringUtils.isBlank(rawPwd) ? (origin == EeConstants.ORIGIN_LOCAL ? defaultPwd : UUID.randomUUID().toString()) : rawPwd));
        user.setCreateTime(now);
        user.setUpdateTime(now);
        user.setLoginFailCount(0);
        user.setMfaBound(false);
        perUserRepository.save(user);
        if (oid != null) {
            orgManage.addMember(user.getId(), oid);
            List<Long> roleIds = creator.getRoleIds();
            if (CollectionUtils.isEmpty(roleIds)) {
                // 未选择角色时授予组织内置的普通用户角色，避免用户进入组织后没有任何权限
                PerRole staff = roleManage.builtinRole(oid, EeConstants.ROLE_TYPE_STAFF);
                roleIds = staff == null ? List.of() : List.of(staff.getId());
            }
            roleManage.replaceRoles(user.getId(), oid, roleIds);
        }
        if (creator.getVariables() != null) {
            variables().saveUserVariables(user.getId(), creator.getVariables());
        }
        return user.getId();
    }

    @Transactional
    public void edit(UserEditor editor) {
        edit(editor, false);
    }

    /**
     * 修改用户
     *
     * @param keepOrg true 时为组织内编辑：editor.defaultOid 为当前组织，只替换该组织内的角色；
     *                false 时为系统用户编辑：editor.defaultOid 为主组织
     */
    @Transactional
    public void edit(UserEditor editor, boolean keepOrg) {
        PerUser user = require(editor.getId());
        QPerUser q = QPerUser.perUser;
        checkEmail(editor.getEmail(), user.getId());
        var update = queryFactory.update(q).where(q.id.eq(user.getId()))
                .set(q.updateTime, System.currentTimeMillis());
        if (StringUtils.isNotBlank(editor.getName())) {
            update.set(q.name, editor.getName().trim());
        }
        setNullable(update, q.email, StringUtils.trimToNull(editor.getEmail()));
        setNullable(update, q.phone, StringUtils.trimToNull(editor.getPhone()));
        setNullable(update, q.phonePrefix, StringUtils.trimToNull(editor.getPhonePrefix()));
        if (editor.getMfaEnable() != null) {
            update.set(q.mfaEnable, editor.getMfaEnable());
        }
        boolean protectedUser = EeConstants.ADMIN_UID.equals(user.getId()) || user.getId().equals(V3UserUtil.getUid());
        if (editor.getEnable() != null && !protectedUser) {
            update.set(q.enable, editor.getEnable());
        }
        Long oid = editor.getDefaultOid();
        if (!EeConstants.ADMIN_UID.equals(user.getId()) && oid != null && !orgManage.exists(oid)) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        update.execute();
        if (!EeConstants.ADMIN_UID.equals(user.getId())) {
            if (keepOrg) {
                // 组织内编辑：只替换该组织内的角色，不改变组织关系
                if (oid != null && orgManage.isMember(user.getId(), oid)) {
                    roleManage.replaceRoles(user.getId(), oid, editor.getRoleIds());
                }
            } else {
                // 系统用户编辑：表单中的组织为主组织，更换主组织时离开原主组织（其他组织的成员关系不变）
                Long home = orgManage.homeOid(user);
                if (!Objects.equals(oid, home)) {
                    if (home != null) {
                        leaveOrg(user.getId(), home);
                    }
                    if (oid != null) {
                        orgManage.addMember(user.getId(), oid);
                        queryFactory.update(q).set(q.defaultOid, oid).where(q.id.eq(user.getId())).execute();
                    }
                }
                if (oid != null) {
                    roleManage.replaceRoles(user.getId(), oid, editor.getRoleIds());
                }
            }
        }
        if (editor.getVariables() != null) {
            variables().saveUserVariables(user.getId(), editor.getVariables());
        }
        userProvider.evict(user.getId());
    }

    /**
     * 个人信息修改：只允许修改姓名、邮箱、手机号、MFA 开关
     */
    @Transactional
    public void personEdit(Long uid, UserEditor editor, boolean mfaForced) {
        PerUser user = require(uid);
        checkEmail(editor.getEmail(), uid);
        QPerUser q = QPerUser.perUser;
        var update = queryFactory.update(q).where(q.id.eq(uid)).set(q.updateTime, System.currentTimeMillis());
        if (StringUtils.isNotBlank(editor.getName())) {
            update.set(q.name, editor.getName().trim());
        }
        setNullable(update, q.email, StringUtils.trimToNull(editor.getEmail()));
        setNullable(update, q.phone, StringUtils.trimToNull(editor.getPhone()));
        setNullable(update, q.phonePrefix, StringUtils.trimToNull(editor.getPhonePrefix()));
        if (editor.getMfaEnable() != null && !(mfaForced && !editor.getMfaEnable())) {
            update.set(q.mfaEnable, editor.getMfaEnable());
        }
        update.execute();
        userProvider.evict(user.getId());
    }

    @Transactional
    public void delete(Long uid) {
        if (EeConstants.ADMIN_UID.equals(uid)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        if (uid.equals(V3UserUtil.getUid())) {
            DEException.throwException(Translator.get("ee_cannot_operate_self"));
        }
        roleManage.removeUser(uid);
        orgManage.removeUser(uid);
        grantManage.deleteBySubject(EeConstants.SUBJECT_USER, uid);
        QPerUserPlatform platform = QPerUserPlatform.perUserPlatform;
        queryFactory.delete(platform).where(platform.uid.eq(uid)).execute();
        variables().deleteUser(uid);
        perUserRepository.deleteById(uid);
        userProvider.evict(uid);
    }

    @Transactional
    public void enable(Long uid, boolean enable) {
        if (EeConstants.ADMIN_UID.equals(uid)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        if (uid.equals(V3UserUtil.getUid())) {
            DEException.throwException(Translator.get("ee_cannot_operate_self"));
        }
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.enable, enable).set(q.updateTime, System.currentTimeMillis()).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    @Transactional
    public void resetPwd(Long uid) {
        PerUser user = require(uid);
        if (!Objects.equals(user.getOrigin(), EeConstants.ORIGIN_LOCAL)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.pwd, PasswordUtils.hash(defaultPwd)).setNull(q.pwdUpdateTime)
                .set(q.loginFailCount, 0).setNull(q.lockUntil).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    @Transactional
    public void modifyPwd(Long uid, String oldPwd, String newPwd) {
        PerUser user = require(uid);
        if (!PasswordUtils.matches(oldPwd, user.getPwd())) {
            DEException.throwException(Translator.get("i18n_user_old_pwd_error"));
        }
        if (!PasswordUtils.strong(newPwd)) {
            DEException.throwException(Translator.get("i18n_user_new_pwd_error"));
        }
        if (StringUtils.equals(oldPwd, newPwd)) {
            DEException.throwException(Translator.get("i18n_user_pwd_same_error"));
        }
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.pwd, PasswordUtils.hash(newPwd)).set(q.pwdUpdateTime, System.currentTimeMillis())
                .where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    @Transactional
    public void switchLanguage(Long uid, String lang) {
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.language, StringUtils.defaultIfBlank(lang, "zh-CN")).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    /**
     * 加入组织（已是成员时只更新角色），不影响用户在其他组织的成员关系。
     * 用户没有有效的主组织时以该组织为主组织。
     *
     * @param roleIds 该组织内的角色；为空且用户在该组织内没有角色时授予内置普通用户角色
     */
    @Transactional
    public void joinOrg(Long uid, Long oid, Collection<Long> roleIds) {
        require(uid);
        if (oid == null || !orgManage.exists(oid)) {
            DEException.throwException(Translator.get("ee_org_not_exist"));
        }
        orgManage.addMember(uid, oid);
        QPerUser qu = QPerUser.perUser;
        Long home = queryFactory.select(qu.defaultOid).from(qu).where(qu.id.eq(uid)).fetchFirst();
        if (home == null || !orgManage.isMember(uid, home)) {
            QPerUser q = QPerUser.perUser;
            queryFactory.update(q).set(q.defaultOid, oid).set(q.updateTime, System.currentTimeMillis()).where(q.id.eq(uid)).execute();
        }
        if (CollectionUtils.isNotEmpty(roleIds)) {
            roleManage.replaceRoles(uid, oid, roleIds);
        } else if (roleManage.roleIdsOfUser(uid, oid).isEmpty()) {
            PerRole staff = roleManage.builtinRole(oid, EeConstants.ROLE_TYPE_STAFF);
            if (staff != null) {
                roleManage.replaceRoles(uid, oid, List.of(staff.getId()));
            }
        }
        userProvider.evict(uid);
    }

    /**
     * 离开组织：删除成员关系、该组织内的角色与对该用户的授权；
     * 主组织 / 最近切换的组织为该组织时改为其他所属组织（没有时置空）。
     */
    @Transactional
    public void leaveOrg(Long uid, Long oid) {
        if (uid == null || oid == null) {
            return;
        }
        orgManage.removeMember(uid, oid);
        roleManage.removeUserInOrg(uid, oid);
        grantManage.deleteBySubjectInOrg(EeConstants.SUBJECT_USER, uid, oid);
        reassignOrgs(uid, oid);
        userProvider.evict(uid);
    }

    /**
     * 组织删除后：删除全部成员关系，并为受影响的用户重新确定主组织 / 最近切换的组织
     */
    @Transactional
    public void onOrgDeleted(Long oid) {
        List<Long> members = orgManage.memberIds(oid);
        orgManage.removeOrg(oid);
        QPerUser q = QPerUser.perUser;
        Set<Long> affected = new LinkedHashSet<>(members);
        affected.addAll(queryFactory.select(q.id).from(q).where(q.defaultOid.eq(oid).or(q.lastOid.eq(oid))).fetch());
        for (Long uid : affected) {
            reassignOrgs(uid, oid);
            userProvider.evict(uid);
        }
    }

    /**
     * 用户不再属于 removedOid 后修正 default_oid / last_oid
     */
    private void reassignOrgs(Long uid, Long removedOid) {
        QPerUser q = QPerUser.perUser;
        // 读取列值而不是实体，避免同一事务内批量更新后持久化上下文中的实体过期
        com.querydsl.core.Tuple t = queryFactory.select(q.defaultOid, q.lastOid).from(q).where(q.id.eq(uid)).fetchFirst();
        if (t == null) {
            return;
        }
        Long defaultOid = t.get(q.defaultOid);
        Long lastOid = t.get(q.lastOid);
        List<Long> remain = orgManage.memberOids(uid);
        if (defaultOid != null && (Objects.equals(defaultOid, removedOid) || !remain.contains(defaultOid))) {
            var update = queryFactory.update(q).where(q.id.eq(uid)).set(q.updateTime, System.currentTimeMillis());
            setNullable(update, q.defaultOid, remain.isEmpty() ? null : remain.getFirst());
            update.execute();
        }
        if (Objects.equals(lastOid, removedOid)) {
            queryFactory.update(q).setNull(q.lastOid).where(q.id.eq(uid)).execute();
        }
    }

    /**
     * 记录最近切换的组织
     */
    @Transactional
    public void switchOrg(Long uid, Long oid) {
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.lastOid, oid).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    /**
     * 用户列表查询条件
     */
    public BooleanBuilder gridCondition(UserGridRequest request, Long scopeOid) {
        QPerUser q = QPerUser.perUser;
        BooleanBuilder where = new BooleanBuilder();
        if (request != null) {
            if (StringUtils.isNotBlank(request.getKeyword())) {
                String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
                where.and(q.name.lower().like(keyword).or(q.account.lower().like(keyword)).or(q.email.lower().like(keyword)));
            }
            if (CollectionUtils.isNotEmpty(request.getStatusList())) {
                where.and(q.enable.in(request.getStatusList()));
            }
            if (CollectionUtils.isNotEmpty(request.getOriginList())) {
                where.and(q.origin.in(request.getOriginList()));
            }
            if (scopeOid == null && request.getOid() != null) {
                if (request.getOid() == -1L) {
                    where.and(orgManage.memberOfNone(q.id));
                } else {
                    where.and(orgManage.memberOf(q.id, request.getOid()));
                }
            }
            if (CollectionUtils.isNotEmpty(request.getRoleIdList())) {
                QPerUserRole ur = QPerUserRole.perUserRole;
                where.and(JPAExpressions.selectOne().from(ur).where(ur.uid.eq(q.id), ur.rid.in(request.getRoleIdList())).exists());
            }
        }
        if (scopeOid != null) {
            where.and(orgManage.memberOf(q.id, scopeOid));
        }
        return where;
    }

    public JPAQuery<PerUser> gridQuery(UserGridRequest request, Long scopeOid) {
        QPerUser q = QPerUser.perUser;
        JPAQuery<PerUser> query = queryFactory.selectFrom(q).where(gridCondition(request, scopeOid));
        boolean asc = request != null && Boolean.FALSE.equals(request.getTimeDesc());
        query.orderBy(asc ? q.createTime.asc() : q.createTime.desc(), q.id.asc());
        return query;
    }

    private void checkEmail(String email, Long excludeId) {
        String value = StringUtils.trimToNull(email);
        if (value == null) {
            return;
        }
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectOne().from(q).where(q.email.eq(value));
        if (excludeId != null) {
            query.where(q.id.ne(excludeId));
        }
        if (query.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_email_exists"));
        }
    }

    private <T> void setNullable(com.querydsl.jpa.impl.JPAUpdateClause update, com.querydsl.core.types.Path<T> path, T value) {
        if (value == null) {
            update.setNull(path);
        } else {
            update.set(path, value);
        }
    }
}
