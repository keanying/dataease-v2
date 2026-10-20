package io.dataease.ee.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.login.vo.MfaQrVO;
import io.dataease.auth.vo.MfaItem;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.permissions.user.EeUserProvider;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.RsaUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MFA（TOTP）管理：全局策略、待完成的两步登录、密钥绑定。
 *
 * <p>策略 {@code mfa.status}：0 未启用，1 所有用户，2 仅系统管理员；用户也可在个人中心自行开启。</p>
 */
@Component
public class EeMfaManage {

    private static final long PENDING_TTL = 5 * 60 * 1000L;

    private final PerUserRepository perUserRepository;

    private final JPAQueryFactory queryFactory;

    private final EeSettingManage settingManage;

    private final EeUserProvider userProvider;

    /**
     * 已通过第一步校验、等待 MFA 的登录：uid -> 待处理信息
     */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 个人中心绑定流程中生成、尚未确认的密钥
     */
    private final Map<Long, String> unconfirmed = new ConcurrentHashMap<>();

    public EeMfaManage(PerUserRepository perUserRepository, JPAQueryFactory queryFactory, EeSettingManage settingManage, EeUserProvider userProvider) {
        this.perUserRepository = perUserRepository;
        this.queryFactory = queryFactory;
        this.settingManage = settingManage;
        this.userProvider = userProvider;
    }

    public int status() {
        return settingManage.getInt("mfa.status", 0);
    }

    public boolean required(PerUser user, boolean platformLogin) {
        if (platformLogin && !settingManage.getBool("mfa.platformEnable", false)) {
            return false;
        }
        int status = status();
        return status == 1 || (status == 2 && V3UserUtil.isSysAdmin(user.getId())) || Boolean.TRUE.equals(user.getMfaEnable());
    }

    /**
     * 第一步校验通过后登记待处理的 MFA 登录
     */
    public MfaItem begin(PerUser user) {
        pending.entrySet().removeIf(entry -> entry.getValue().expireAt < System.currentTimeMillis());
        pending.put(user.getId(), new Pending(System.currentTimeMillis() + PENDING_TTL, null));
        MfaItem item = new MfaItem();
        item.setEnabled(true);
        item.setReady(Boolean.TRUE.equals(user.getMfaBound()) && StringUtils.isNotBlank(user.getMfaSecret()));
        item.setUid(user.getId());
        return item;
    }

    /**
     * 登录流程中获取绑定二维码（仅限待处理且尚未绑定的用户）
     */
    public MfaQrVO loginQr(Long uid) {
        Pending item = pending.get(uid);
        if (item == null || item.expireAt < System.currentTimeMillis()) {
            DEException.throwException(Translator.get("ee_mfa_expired"));
        }
        PerUser user = perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        if (Boolean.TRUE.equals(user.getMfaBound())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        String secret = item.secret == null ? TotpUtils.generateSecret() : item.secret;
        pending.put(uid, new Pending(item.expireAt, secret));
        return qr(user, secret);
    }

    /**
     * 完成 MFA 登录，返回是否通过
     */
    @Transactional
    public boolean finishLogin(Long uid, String code) {
        Pending item = pending.get(uid);
        if (item == null || item.expireAt < System.currentTimeMillis()) {
            DEException.throwException(Translator.get("ee_mfa_expired"));
        }
        PerUser user = perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        boolean bound = Boolean.TRUE.equals(user.getMfaBound()) && StringUtils.isNotBlank(user.getMfaSecret());
        String secret = bound ? decrypt(user.getMfaSecret()) : item.secret;
        if (!TotpUtils.verify(secret, code, rate())) {
            DEException.throwException(Translator.get("ee_mfa_code_error"));
        }
        if (!bound) {
            saveSecret(uid, secret);
        }
        pending.remove(uid);
        return true;
    }

    /**
     * 个人中心：生成待确认的绑定二维码
     */
    public MfaQrVO bindQr(Long uid) {
        PerUser user = perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            DEException.throwException(Translator.get("ee_user_not_exist"));
        }
        String secret = TotpUtils.generateSecret();
        unconfirmed.put(uid, secret);
        return qr(user, secret);
    }

    @Transactional
    public void bind(Long uid, String code) {
        String secret = unconfirmed.get(uid);
        if (secret == null || !TotpUtils.verify(secret, code, rate())) {
            DEException.throwException(Translator.get("ee_mfa_code_error"));
        }
        saveSecret(uid, secret);
        unconfirmed.remove(uid);
    }

    /**
     * 解绑，成功返回 null，失败返回错误提示
     */
    @Transactional
    public String unbind(Long uid, String code) {
        PerUser user = perUserRepository.findById(uid).orElse(null);
        if (user == null || StringUtils.isBlank(user.getMfaSecret())) {
            return null;
        }
        if (!TotpUtils.verify(decrypt(user.getMfaSecret()), code, rate())) {
            return Translator.get("ee_mfa_code_error");
        }
        reset(uid);
        return null;
    }

    @Transactional
    public void reset(Long uid) {
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).setNull(q.mfaSecret).set(q.mfaBound, false).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    public boolean bound(Long uid) {
        return perUserRepository.findById(uid).map(user -> Boolean.TRUE.equals(user.getMfaBound())).orElse(false);
    }

    private void saveSecret(Long uid, String secret) {
        QPerUser q = QPerUser.perUser;
        queryFactory.update(q).set(q.mfaSecret, RsaUtils.symmetricEncrypt(secret)).set(q.mfaBound, true).where(q.id.eq(uid)).execute();
        userProvider.evict(uid);
    }

    private MfaQrVO qr(PerUser user, String secret) {
        String issuer = settingManage.get("mfa.otpName", "DataEase");
        MfaQrVO vo = new MfaQrVO();
        vo.setImg(TotpUtils.qrDataUri(TotpUtils.otpAuthUri(issuer, user.getAccount(), secret)));
        return vo;
    }

    private int rate() {
        return settingManage.getInt("mfa.rate", 1);
    }

    private String decrypt(String value) {
        return RsaUtils.symmetricDecrypt(value);
    }

    private record Pending(long expireAt, String secret) {
    }
}
