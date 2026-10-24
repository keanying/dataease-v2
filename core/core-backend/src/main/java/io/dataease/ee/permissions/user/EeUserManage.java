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
        if (!EeConstants.ADMIN_UID.equals(user.getId())) {
            if (oid != null && !orgManage.exists(oid)) {
                DEException.throwException(Translator.get("ee_org_not_exist"));
            }
            setNullable(update, q.defaultOid, oid);
        }
        update.execute();
        if (!EeConstants.ADMIN_UID.equals(user.getId())) {
            if (!Objects.equals(oid, user.getDefaultOid()) && user.getDefaultOid() != null) {
                grantManage.deleteBySubjectInOrg(EeConstants.SUBJECT_USER, user.getId(), user.getDefaultOid());
            }
            roleManage.replaceRoles(user.getId(), oid, editor.getRoleIds());
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
     * 把用户移入组织（或移出组织 oid=null），并替换角色
     */
    @Transactional
    public void moveToOrg(Long uid, Long oid, Collection<Long> roleIds) {
        PerUser user = require(uid);
        if (user.getDefaultOid() != null && !Objects.equals(user.getDefaultOid(), oid)) {
            grantManage.deleteBySubjectInOrg(EeConstants.SUBJECT_USER, uid, user.getDefaultOid());
        }
        QPerUser q = QPerUser.perUser;
        var update = queryFactory.update(q).where(q.id.eq(uid)).set(q.updateTime, System.currentTimeMillis());
        setNullable(update, q.defaultOid, oid);
        update.execute();
        roleManage.replaceRoles(uid, oid, roleIds);
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
                    where.and(q.defaultOid.isNull());
                } else {
                    where.and(q.defaultOid.eq(request.getOid()));
                }
            }
            if (CollectionUtils.isNotEmpty(request.getRoleIdList())) {
                QPerUserRole ur = QPerUserRole.perUserRole;
                where.and(JPAExpressions.selectOne().from(ur).where(ur.uid.eq(q.id), ur.rid.in(request.getRoleIdList())).exists());
            }
        }
        if (scopeOid != null) {
            where.and(q.defaultOid.eq(scopeOid));
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
