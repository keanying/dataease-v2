package io.dataease.ee.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.auth.vo.InvalidPwdVO;
import io.dataease.auth.vo.TokenVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.result.ResultCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 登录校验：账号锁定策略、密码有效期、MFA、签发 token。
 */
@Component
public class EeLoginManage {

    private static final long DAY = 24 * 60 * 60 * 1000L;

    private final PerUserRepository perUserRepository;

    private final JPAQueryFactory queryFactory;

    private final EeSettingManage settingManage;

    private final EeTokenManage tokenManage;

    private final EeMfaManage mfaManage;

    private final EeOrgManage orgManage;

    private final EeUserProvider userProvider;

    private final List<EeExternalLoginProvider> externalProviders;

    public EeLoginManage(PerUserRepository perUserRepository, JPAQueryFactory queryFactory, EeSettingManage settingManage,
                         EeTokenManage tokenManage, EeMfaManage mfaManage, EeOrgManage orgManage, EeUserProvider userProvider,
                         List<EeExternalLoginProvider> externalProviders) {
        this.perUserRepository = perUserRepository;
        this.queryFactory = queryFactory;
        this.settingManage = settingManage;
        this.tokenManage = tokenManage;
        this.mfaManage = mfaManage;
        this.orgManage = orgManage;
        this.userProvider = userProvider;
        this.externalProviders = externalProviders;
    }

    public PerUser findByAccountOrEmail(String name) {
        QPerUser q = QPerUser.perUser;
        PerUser user = queryFactory.selectFrom(q).where(q.account.eq(name)).fetchFirst();
        if (user == null && StringUtils.contains(name, "@")) {
            user = queryFactory.selectFrom(q).where(q.email.eq(name)).fetchFirst();
        }
        return user;
    }

    /**
     * 本地账号密码登录
     */
    @Transactional(noRollbackFor = DEException.class)
    public TokenVO localLogin(String account, String pwd) {
        PerUser user = findByAccountOrEmail(account);
        if (user == null) {
            DEException.throwException(Translator.get("i18n_login_name_pwd_err"));
        }
        checkLock(user);
        if (!PasswordUtils.matches(pwd, user.getPwd())) {
            onFailure(user);
        }
        if (!Boolean.TRUE.equals(user.getEnable())) {
            DEException.throwException(Translator.get("i18n_user_disable"));
        }
        onSuccess(user);
        InvalidPwdVO invalidPwd = invalidPwd(user, pwd);
        TokenVO tokenVO;
        if (mfaManage.required(user, false)) {
            tokenVO = new TokenVO(null, 0L);
            tokenVO.setMfa(mfaManage.begin(user));
        } else {
            tokenVO = issue(user);
        }
        tokenVO.setInvalidPwd(invalidPwd);
        return tokenVO;
    }

    /**
     * 外部账号密码登录（LDAP）
     */
    public TokenVO externalPasswordLogin(int origin, String account, String pwd) {
        EeExternalLoginProvider provider = provider(origin);
        PerUser user = provider.passwordLogin(account, pwd);
        // 前端 LDAP 登录不处理 MFA 步骤（views/login/index.vue），因此不要求 MFA
        return finishExternal(user, false, true);
    }

    /**
     * 第三方回调登录
     */
    public TokenVO platformLogin(int origin) {
        EeExternalLoginProvider provider = provider(origin);
        PerUser user = provider.platformLogin();
        return finishExternal(user, true, false);
    }

    private TokenVO finishExternal(PerUser user, boolean platform, boolean skipMfa) {
        if (user == null) {
            DEException.throwException(Translator.get("ee_platform_user_not_bound"));
        }
        if (!Boolean.TRUE.equals(user.getEnable())) {
            DEException.throwException(Translator.get("i18n_user_disable"));
        }
        if (!skipMfa && mfaManage.required(user, platform)) {
            TokenVO tokenVO = new TokenVO(null, 0L);
            tokenVO.setMfa(mfaManage.begin(user));
            return tokenVO;
        }
        return issue(user);
    }

    public TokenVO issue(PerUser user) {
        userProvider.evict(user.getId());
        return tokenManage.generate(user.getId(), orgManage.resolveCurrentOid(user.getId()));
    }

    public TokenVO issue(Long uid) {
        PerUser user = perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        return issue(user);
    }

    private EeExternalLoginProvider provider(int origin) {
        return externalProviders.stream().filter(item -> item.origin() == origin).findFirst()
                .orElseThrow(() -> new DEException(ResultCode.SYSTEM_INNER_ERROR.code(), Translator.get("ee_platform_not_enabled")));
    }

    /**
     * 是否处于锁定期
     */
    public boolean locked(PerUser user) {
        return user.getLockUntil() != null && user.getLockUntil() > System.currentTimeMillis();
    }

    private void checkLock(PerUser user) {
        if (locked(user)) {
            long minutes = Math.max(1, (user.getLockUntil() - System.currentTimeMillis() + 59_999) / 60_000);
            DEException.throwException(Translator.get("ee_account_locked", minutes));
        }
    }

    private void onFailure(PerUser user) {
        boolean limit = settingManage.getBool("basic.loginLimit", false);
        if (!limit) {
            DEException.throwException(Translator.get("i18n_login_name_pwd_err"));
        }
        int rate = Math.max(1, settingManage.getInt("basic.loginLimitRate", 5));
        int lockMinutes = Math.max(1, settingManage.getInt("basic.loginLimitTime", 10));
        int failCount = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        QPerUser q = QPerUser.perUser;
        if (failCount >= rate) {
            failCount = 0;
            long lockUntil = System.currentTimeMillis() + lockMinutes * 60_000L;
            saveFailure(user.getId(), failCount, lockUntil);
            DEException.throwException(Translator.get("ee_account_locked", lockMinutes));
        }
        saveFailure(user.getId(), failCount, null);
        DEException.throwException(Translator.get("ee_account_lock_remain", rate - failCount));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailure(Long uid, int failCount, Long lockUntil) {
        QPerUser q = QPerUser.perUser;
        var update = queryFactory.update(q).set(q.loginFailCount, failCount).where(q.id.eq(uid));
        if (lockUntil != null) {
            update.set(q.lockUntil, lockUntil);
        }
        update.execute();
    }

    private void onSuccess(PerUser user) {
        if ((user.getLoginFailCount() != null && user.getLoginFailCount() > 0) || user.getLockUntil() != null) {
            unlock(user.getId());
        }
    }

    @Transactional
    public void unlock(Long uid) {
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.loginFailCount, 0).setNull(q.lockUntil).where(q.id.eq(uid)).execute();
    }

    /**
     * 密码有效期：basic.pvp 0 永久，1 一年，2 六个月，3 三个月，4 一个月；basic.dip 为 true 时禁止使用初始密码
     */
    private InvalidPwdVO invalidPwd(PerUser user, String rawPwd) {
        InvalidPwdVO vo = new InvalidPwdVO();
        vo.setUid(user.getId());
        vo.setValidityPeriod(-1L);
        if (EeConstants.ADMIN_UID.equals(user.getId()) && user.getPwdUpdateTime() == null) {
            // 内置管理员首次登录不强制修改初始密码，除非开启了禁用初始密码
            if (!settingManage.getBool("basic.dip", false)) {
                return null;
            }
        }
        if (settingManage.getBool("basic.dip", false) && user.getPwdUpdateTime() == null) {
            vo.setInvalid(true);
            return vo;
        }
        int months = switch (settingManage.get("basic.pvp", "0")) {
            case "1" -> 12;
            case "2" -> 6;
            case "3" -> 3;
            case "4" -> 1;
            default -> 0;
        };
        if (months == 0) {
            return null;
        }
        long base = user.getPwdUpdateTime() != null ? user.getPwdUpdateTime() : user.getCreateTime();
        long expireAt = base + months * 30L * DAY;
        long remain = (expireAt - System.currentTimeMillis()) / DAY;
        if (remain <= 0) {
            vo.setInvalid(true);
            vo.setValidityPeriod(0L);
        } else {
            vo.setValidityPeriod(remain);
        }
        return vo;
    }
}
