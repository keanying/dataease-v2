package io.dataease.ee.sso.support;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.user.dto.UserCreator;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.entity.QPerUserPlatform;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.ee.permissions.user.EeUserManage;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.ee.permissions.user.UserServer;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 外部身份与 DataEase 用户的对应：按绑定关系（per_user_platform）查找，账号类来源再按“同来源同账号”匹配，
 * 均未找到时按系统参数 basic.autoCreateUser / basic.platformOid / basic.platformRid 自动创建。
 *
 * <p>安全约束：外部身份不会匹配到来源不同的同名本地账号（避免 LDAP/IdP 中的 admin 冒用本地 admin）。</p>
 */
@Component
public class EeSsoUserManage {

    private static final int ACCOUNT_MAX = 255;

    private final JPAQueryFactory queryFactory;

    private final EeUserManage userManage;

    private final UserServer userServer;

    private final EeSettingManage settingManage;

    private final EeRoleManage roleManage;

    private final EeOrgManage orgManage;

    private final EeUserProvider userProvider;

    public EeSsoUserManage(JPAQueryFactory queryFactory, EeUserManage userManage, UserServer userServer, EeSettingManage settingManage,
                           EeRoleManage roleManage, EeOrgManage orgManage, EeUserProvider userProvider) {
        this.queryFactory = queryFactory;
        this.userManage = userManage;
        this.userServer = userServer;
        this.settingManage = settingManage;
        this.roleManage = roleManage;
        this.orgManage = orgManage;
        this.userProvider = userProvider;
    }

    /**
     * 外部身份登录时解析（必要时创建）对应用户
     */
    @Transactional
    public PerUser resolve(ExternalIdentity identity) {
        if (identity == null || StringUtils.isBlank(identity.sub())) {
            DEException.throwException(Translator.get("ee_sso_identity_missing"));
        }
        int origin = identity.origin();
        PerUser user = findBound(origin, identity.sub());
        if (user != null) {
            sync(user, identity);
            return userManage.get(user.getId());
        }
        boolean accountBased = EeSsoConstants.accountBased(origin);
        if (accountBased) {
            String account = StringUtils.trimToNull(identity.account());
            if (account == null) {
                DEException.throwException(Translator.get("ee_sso_identity_missing"));
            }
            PerUser exist = userManage.findByAccount(account);
            if (exist != null) {
                if (!Objects.equals(exist.getOrigin(), origin)) {
                    DEException.throwException(Translator.get("ee_sso_account_conflict", account));
                }
                userServer.bindPlatform(exist.getId(), origin, identity.sub());
                sync(exist, identity);
                return userManage.get(exist.getId());
            }
        }
        if (!settingManage.getBool(EeSsoConstants.KEY_AUTO_CREATE_USER, false)) {
            DEException.throwException(Translator.get(accountBased ? "ee_sso_user_not_exist" : "ee_platform_user_not_bound"));
        }
        Long uid = create(identity, accountBased);
        userServer.bindPlatform(uid, origin, identity.sub());
        return userManage.get(uid);
    }

    /**
     * 已绑定该平台账号的用户
     */
    public PerUser findBound(int origin, String sub) {
        if (StringUtils.isBlank(sub)) {
            return null;
        }
        QPerUserPlatform p = QPerUserPlatform.perUserPlatform;
        Long uid = queryFactory.select(p.uid).from(p).where(p.origin.eq(origin), p.platformUid.eq(sub)).fetchFirst();
        return uid == null ? null : userManage.get(uid);
    }

    /**
     * 用户绑定的平台账号
     */
    public Map<Long, String> platformIds(int origin, Collection<Long> uids) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (uids == null || uids.isEmpty()) {
            return result;
        }
        QPerUserPlatform p = QPerUserPlatform.perUserPlatform;
        queryFactory.selectFrom(p).where(p.origin.eq(origin), p.uid.in(uids)).fetch()
                .forEach(item -> result.put(item.getUid(), item.getPlatformUid()));
        return result;
    }

    /**
     * 绑定当前用户与平台账号
     */
    public void bind(Long uid, ExternalIdentity identity) {
        if (uid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        PerUser bound = findBound(identity.origin(), identity.sub());
        if (bound != null && !Objects.equals(bound.getId(), uid)) {
            DEException.throwException(Translator.get("ee_platform_already_bound"));
        }
        userServer.bindPlatform(uid, identity.origin(), identity.sub());
    }

    private Long create(ExternalIdentity identity, boolean accountBased) {
        UserCreator creator = new UserCreator();
        String account = accountBased ? StringUtils.trim(identity.account()) : uniqueAccount(identity);
        if (account.length() > ACCOUNT_MAX) {
            DEException.throwException(Translator.get("ee_sso_identity_missing"));
        }
        creator.setAccount(account);
        creator.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(StringUtils.trimToNull(identity.name()), account), 255));
        creator.setEmail(availableEmail(identity.email(), null));
        creator.setPhone(StringUtils.abbreviate(StringUtils.trimToNull(identity.phone()), 64));
        creator.setEnable(true);
        Long oid = NumberUtils.toLong(settingManage.get(EeSsoConstants.KEY_PLATFORM_OID, null), EeConstants.ROOT_ORG_ID);
        if (!orgManage.exists(oid)) {
            oid = EeConstants.ROOT_ORG_ID;
        }
        creator.setDefaultOid(oid);
        creator.setRoleIds(roleIds(oid));
        return userManage.create(creator, identity.origin(), null);
    }

    private List<Long> roleIds(Long oid) {
        Set<Long> allowed = new HashSet<>();
        for (PerRole role : roleManage.rolesOfOrg(oid)) {
            allowed.add(role.getId());
        }
        List<Long> result = new ArrayList<>();
        for (String item : StringUtils.split(settingManage.get(EeSsoConstants.KEY_PLATFORM_RID, ""), ',')) {
            long rid = NumberUtils.toLong(StringUtils.trim(item), -1);
            if (allowed.contains(rid) && !result.contains(rid)) {
                result.add(rid);
            }
        }
        if (result.isEmpty()) {
            PerRole staff = roleManage.builtinRole(oid, EeConstants.ROLE_TYPE_STAFF);
            if (staff != null) {
                result.add(staff.getId());
            }
        }
        return result;
    }

    /**
     * 平台用户 ID 生成的账号：去除非法字符，冲突时追加随机后缀
     */
    private String uniqueAccount(ExternalIdentity identity) {
        String base = StringUtils.defaultIfBlank(StringUtils.trimToNull(identity.account()), identity.sub());
        base = base.replaceAll("[^a-zA-Z0-9@._-]", "_");
        if (!Character.isLetterOrDigit(base.charAt(0))) {
            base = "u" + base;
        }
        base = StringUtils.left(base, 200);
        String account = base;
        int i = 0;
        while (userManage.findByAccount(account) != null) {
            if (++i > 20) {
                DEException.throwException(Translator.get("ee_account_exists"));
            }
            account = base + "_" + EeSsoKeys.randomHex(2);
        }
        return account;
    }

    private String availableEmail(String email, Long excludeUid) {
        String value = StringUtils.trimToNull(email);
        if (value == null || value.length() > 255 || !value.contains("@")) {
            return null;
        }
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectOne().from(q).where(q.email.eq(value));
        if (excludeUid != null) {
            query.where(q.id.ne(excludeUid));
        }
        return query.fetchFirst() == null ? value : null;
    }

    /**
     * 以身份源为准同步姓名、邮箱（仅限来源一致的用户，不覆盖绑定了平台账号的本地用户）
     */
    private void sync(PerUser user, ExternalIdentity identity) {
        if (!Objects.equals(user.getOrigin(), identity.origin())) {
            return;
        }
        QPerUser q = QPerUser.perUser;
        var update = queryFactory.update(q).where(q.id.eq(user.getId()));
        boolean changed = false;
        String name = StringUtils.abbreviate(StringUtils.trimToNull(identity.name()), 255);
        if (name != null && !name.equals(user.getName())) {
            update.set(q.name, name);
            changed = true;
        }
        String email = availableEmail(identity.email(), user.getId());
        if (email != null && !email.equals(user.getEmail())) {
            update.set(q.email, email);
            changed = true;
        }
        if (changed) {
            update.set(q.updateTime, System.currentTimeMillis()).execute();
            // 批量更新不会刷新持久化上下文（open-in-view 下同一请求后续读取仍是旧实体），同步修改内存中的实体
            if (name != null) {
                user.setName(name);
            }
            if (email != null) {
                user.setEmail(email);
            }
            userProvider.evict(user.getId());
        }
    }
}
