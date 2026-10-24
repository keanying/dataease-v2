package io.dataease.ee.platform;

import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.dao.EeAuthConfig;
import io.dataease.ee.sso.support.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.LogUtil;
import io.dataease.utils.Md5Utils;
import org.apache.commons.lang3.StringUtils;

import java.util.function.Supplier;

/**
 * 平台对接（钉钉 / 飞书 / 国际飞书 / 企业微信）公共逻辑：配置读写、启停、校验状态、扫码登录票据与账号绑定
 *
 * @param <C> 平台配置类型（同时作为 info 返回值）
 */
public abstract class EePlatformManageBase<C> {

    protected final EeAuthConfigManage configManage;

    protected final EeHttp http;

    protected final EeTtlStore ttlStore;

    protected final EeLoginTicketManage ticketManage;

    protected final EeSsoUserManage ssoUserManage;

    protected EePlatformManageBase(EePlatformDeps deps) {
        this.configManage = deps.configManage();
        this.http = deps.http();
        this.ttlStore = deps.ttlStore();
        this.ticketManage = deps.ticketManage();
        this.ssoUserManage = deps.ssoUserManage();
    }

    /**
     * 配置名称（ee_auth_config.name）
     */
    public abstract String name();

    /**
     * 用户来源编码
     */
    public abstract int origin();

    protected abstract Class<C> type();

    /**
     * 校验凭证可用（调用平台接口获取访问令牌等），失败抛出异常
     */
    protected abstract void check(C config);

    /**
     * 凭证摘要，用于判断保存时凭证是否变化
     */
    protected abstract String credentialKey(C config);

    /**
     * 授权码换取平台用户
     */
    protected abstract ExternalIdentity identityByCode(C config, String code, String state);

    protected abstract void fillFlags(C config, boolean enable, boolean valid);

    public C config() {
        return configManage.load(name(), type());
    }

    /**
     * 管理页面查询：包含密钥明文与启用 / 校验状态
     */
    public C info() {
        EeAuthConfig entity = configManage.find(name());
        C config = configManage.parse(entity, type());
        if (config == null) {
            config = newConfig();
        }
        fillFlags(config, entity != null && Boolean.TRUE.equals(entity.getEnable()), entity != null && Boolean.TRUE.equals(entity.getValid()));
        return config;
    }

    public boolean available() {
        return configManage.active(name());
    }

    public C requireActive() {
        EeAuthConfig entity = configManage.find(name());
        C config = configManage.parse(entity, type());
        if (config == null || !Boolean.TRUE.equals(entity.getEnable()) || !Boolean.TRUE.equals(entity.getValid())) {
            DEException.throwException(Translator.get("ee_platform_not_enabled"));
        }
        return config;
    }

    /**
     * 保存配置：凭证变化时重新校验；未通过校验的配置不能启用
     */
    public void save(C config, Boolean enable, Boolean valid) {
        if (config == null) {
            DEException.throwException(Translator.get("ee_sso_config_incomplete"));
        }
        EeAuthConfig entity = configManage.find(name());
        C old = configManage.parse(entity, type());
        boolean changed = old == null || !StringUtils.equals(credentialKey(old), credentialKey(config));
        boolean newValid;
        if (changed) {
            newValid = tryCheck(config) == null;
        } else {
            newValid = (entity != null && Boolean.TRUE.equals(entity.getValid())) || Boolean.TRUE.equals(valid);
        }
        fillFlags(config, false, false);
        configManage.save(name(), config, newValid, Boolean.TRUE.equals(enable) && newValid);
    }

    public void switchEnable(boolean enable) {
        EeAuthConfig entity = configManage.find(name());
        if (entity == null) {
            DEException.throwException(Translator.get("ee_sso_config_not_found"));
        }
        if (enable && !Boolean.TRUE.equals(entity.getValid())) {
            DEException.throwException(Translator.get("ee_sso_validate_first"));
        }
        configManage.updateFlags(entity.getId(), enable, null);
    }

    /**
     * 校验：失败抛出异常；被校验的凭证与已保存凭证一致时同步保存校验结果
     */
    public void validate(C config) {
        String error = tryCheck(config);
        EeAuthConfig entity = configManage.find(name());
        C stored = configManage.parse(entity, type());
        if (stored != null && StringUtils.equals(credentialKey(stored), credentialKey(config))) {
            configManage.updateFlags(entity.getId(), null, error == null);
        }
        if (error != null) {
            DEException.throwException(Translator.get("ee_platform_validate_failed", error));
        }
    }

    /**
     * 扫码 / 应用内免登：授权码换取临时登录票据
     */
    public String token(String code, String state) {
        C config = requireActive();
        if (StringUtils.isBlank(code)) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
        return ticketManage.issue(identity(config, code, state));
    }

    /**
     * 个人中心绑定当前用户
     */
    public void bind(String code, String state) {
        C config = requireActive();
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (StringUtils.isBlank(code)) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
        ssoUserManage.bind(uid, identity(config, code, state));
    }

    private ExternalIdentity identity(C config, String code, String state) {
        try {
            ExternalIdentity identity = identityByCode(config, code, state);
            if (identity == null || StringUtils.isBlank(identity.sub())) {
                DEException.throwException(Translator.get("ee_sso_identity_missing"));
            }
            return identity;
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error(name() + " code exchange failed: " + e.getMessage());
            DEException.throwException(Translator.get("ee_sso_remote_failed", e.getMessage()));
            return null;
        }
    }

    protected String tryCheck(C config) {
        try {
            check(config);
            return null;
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    /**
     * 访问令牌缓存（集群共享，90 分钟；平台令牌有效期均为 2 小时）
     */
    protected String cachedToken(String kind, String appKey, String secret, Supplier<String> loader) {
        String key = name() + ":" + kind + ":" + appKey + ":" + Md5Utils.md5(StringUtils.defaultString(secret));
        String cached = ttlStore.get(EeSsoConstants.CACHE_PLATFORM_TOKEN, key);
        if (StringUtils.isNotBlank(cached)) {
            return cached;
        }
        String token = loader.get();
        if (StringUtils.isNotBlank(token)) {
            ttlStore.put(EeSsoConstants.CACHE_PLATFORM_TOKEN, key, token, EeSsoConstants.PLATFORM_TOKEN_TTL_MINUTES);
        }
        return token;
    }

    protected void evictToken(String kind, String appKey, String secret) {
        String key = name() + ":" + kind + ":" + appKey + ":" + Md5Utils.md5(StringUtils.defaultString(secret));
        ttlStore.remove(EeSsoConstants.CACHE_PLATFORM_TOKEN, key);
    }

    protected C newConfig() {
        try {
            return type().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 平台接口错误
     */
    public static class PlatformApiException extends RuntimeException {
        public PlatformApiException(String message) {
            super(message);
        }
    }
}
