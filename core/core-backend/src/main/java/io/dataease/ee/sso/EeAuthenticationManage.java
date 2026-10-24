package io.dataease.ee.sso;

import io.dataease.api.xpack.settings.vo.*;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.sso.cas.EeCasManage;
import io.dataease.ee.sso.dao.EeAuthConfig;
import io.dataease.ee.sso.gateway.EeApisixRouteManage;
import io.dataease.ee.sso.gateway.EeSsoGatewaySupport;
import io.dataease.ee.sso.ldap.EeLdapManage;
import io.dataease.ee.sso.oauth2.EeOauth2Manage;
import io.dataease.ee.sso.oidc.EeOidcManage;
import io.dataease.ee.sso.saml.EeSaml2Manage;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.license.utils.LicenseUtil;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 认证设置（LDAP / OIDC / CAS / OAuth2 / SAML2）与登录页认证状态
 */
@Component
public class EeAuthenticationManage {

    private final EeAuthConfigManage configManage;

    private final EeLdapManage ldapManage;

    private final EeOidcManage oidcManage;

    private final EeCasManage casManage;

    private final EeOauth2Manage oauth2Manage;

    private final EeSaml2Manage saml2Manage;

    private final EeApisixRouteManage apisixRouteManage;

    private final EeSsoGatewaySupport gatewaySupport;

    private final EeSettingManage settingManage;

    public EeAuthenticationManage(EeAuthConfigManage configManage, EeLdapManage ldapManage, EeOidcManage oidcManage, EeCasManage casManage,
                                  EeOauth2Manage oauth2Manage, EeSaml2Manage saml2Manage, EeApisixRouteManage apisixRouteManage,
                                  EeSsoGatewaySupport gatewaySupport, EeSettingManage settingManage) {
        this.configManage = configManage;
        this.ldapManage = ldapManage;
        this.oidcManage = oidcManage;
        this.casManage = casManage;
        this.oauth2Manage = oauth2Manage;
        this.saml2Manage = saml2Manage;
        this.apisixRouteManage = apisixRouteManage;
        this.gatewaySupport = gatewaySupport;
        this.settingManage = settingManage;
    }

    public List<XpackAuthenticationVO> grid() {
        Map<String, EeAuthConfig> all = configManage.all();
        List<XpackAuthenticationVO> result = new ArrayList<>();
        for (String name : EeSsoConstants.AUTH_PROVIDERS) {
            EeAuthConfig entity = all.get(name);
            XpackAuthenticationVO vo = new XpackAuthenticationVO();
            vo.setName(name);
            if (entity != null) {
                vo.setId(entity.getId());
                vo.setEnable(Boolean.TRUE.equals(entity.getEnable()));
                vo.setValid(Boolean.TRUE.equals(entity.getValid()));
            }
            result.add(vo);
        }
        return result;
    }

    public void switchEnable(Long id, boolean enable) {
        EeAuthConfig entity = requireProvider(id);
        if (enable && !Boolean.TRUE.equals(entity.getValid())) {
            DEException.throwException(Translator.get("ee_sso_validate_first"));
        }
        configManage.updateFlags(entity.getId(), enable, null);
        afterChange(entity.getName(), enable);
    }

    public String validateId(Long id) {
        EeAuthConfig entity = requireProvider(id);
        String result = switch (entity.getName()) {
            case EeSsoConstants.LDAP -> ldapManage.validate(configManage.parse(entity, XpackLdapVO.class));
            case EeSsoConstants.OIDC -> oidcManage.validate(configManage.parse(entity, XpackOidcVO.class));
            case EeSsoConstants.CAS -> casManage.validate(configManage.parse(entity, XpackCasVO.class));
            case EeSsoConstants.OAUTH2 -> oauth2Manage.validate(configManage.parse(entity, XpackOauth2VO.class));
            case EeSsoConstants.SAML2 -> saml2Manage.validate(configManage.parse(entity, XpackSaml2VO.class));
            default -> Translator.get("ee_no_permission");
        };
        boolean valid = "true".equals(result);
        configManage.updateFlags(entity.getId(), null, valid);
        if (!valid) {
            afterChange(entity.getName(), false);
        }
        gatewaySupport.evict(entity.getName());
        return result;
    }

    public <T> T info(String name, Class<T> type) {
        T value = configManage.load(name, type);
        if (value != null) {
            return value;
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public XpackSaml2VO samlInfo() {
        EeSaml2Manage.EeSaml2Config config = configManage.load(EeSsoConstants.SAML2, EeSaml2Manage.EeSaml2Config.class);
        if (config == null) {
            return new XpackSaml2VO();
        }
        XpackSaml2VO vo = EeJson.convert(config, XpackSaml2VO.class);
        vo.setPrivateKey(config.getPrivateKey());
        vo.setCertificate(config.getCertificate());
        return vo;
    }

    public String saveLdap(XpackLdapVO vo) {
        String result = ldapManage.validate(vo);
        return save(EeSsoConstants.LDAP, vo, result);
    }

    public String saveOidc(XpackOidcVO vo) {
        String result = oidcManage.validate(vo);
        return save(EeSsoConstants.OIDC, vo, result);
    }

    public String saveCas(XpackCasVO vo) {
        String result = casManage.validate(vo);
        return save(EeSsoConstants.CAS, vo, result);
    }

    public String saveOauth2(XpackOauth2VO vo) {
        XpackOauth2VO old = configManage.load(EeSsoConstants.OAUTH2, XpackOauth2VO.class);
        if (old != null && vo != null && vo.getLogoutEndpoint() == null) {
            vo.setLogoutEndpoint(old.getLogoutEndpoint());
        }
        if (vo != null && StringUtils.isBlank(vo.getAuthMethod())) {
            vo.setAuthMethod("0");
        }
        String result = oauth2Manage.validate(vo);
        return save(EeSsoConstants.OAUTH2, vo, result);
    }

    public String saveSaml2(XpackSaml2VO vo) {
        Object config;
        String result;
        try {
            config = saml2Manage.prepare(vo);
            result = "true";
        } catch (Exception e) {
            LogUtil.warn("saml2 config invalid: " + e.getMessage());
            EeSaml2Manage.EeSaml2Config raw = EeJson.convert(vo, EeSaml2Manage.EeSaml2Config.class);
            raw.setPrivateKey(vo.getPrivateKey());
            raw.setCertificate(vo.getCertificate());
            config = raw;
            result = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
        return save(EeSsoConstants.SAML2, config, result);
    }

    public String validate(String name, Object vo) {
        return switch (name) {
            case EeSsoConstants.LDAP -> ldapManage.validate((XpackLdapVO) vo);
            case EeSsoConstants.OIDC -> oidcManage.validate((XpackOidcVO) vo);
            case EeSsoConstants.CAS -> casManage.validate((XpackCasVO) vo);
            case EeSsoConstants.OAUTH2 -> oauth2Manage.validate((XpackOauth2VO) vo);
            case EeSsoConstants.SAML2 -> saml2Manage.validate((XpackSaml2VO) vo);
            default -> Translator.get("ee_no_permission");
        };
    }

    /**
     * 登录页认证状态：任何异常都返回全部 false，保证登录页可用
     */
    public List<XpackAuthenticationStatusVO> status() {
        List<XpackAuthenticationStatusVO> result = new ArrayList<>();
        Map<String, EeAuthConfig> all = Map.of();
        try {
            if (LicenseUtil.licenseValid()) {
                all = configManage.all();
            }
        } catch (Throwable e) {
            LogUtil.error("query authentication status failed: " + e.getMessage());
        }
        boolean qrcode = false;
        for (String name : EeSsoConstants.STATUS_NAMES) {
            boolean enable;
            if (EeSsoConstants.QRCODE.equals(name)) {
                enable = qrcode;
            } else {
                EeAuthConfig entity = all.get(name);
                enable = entity != null && Boolean.TRUE.equals(entity.getEnable()) && Boolean.TRUE.equals(entity.getValid());
                if (enable && EeSsoConstants.PLATFORMS.contains(name)) {
                    qrcode = true;
                }
            }
            XpackAuthenticationStatusVO vo = new XpackAuthenticationStatusVO();
            vo.setName(name);
            vo.setEnable(enable);
            result.add(vo);
        }
        return result;
    }

    /**
     * 登录页默认登录方式（供 /sysParameter/defaultLogin 使用）：basic.defaultLogin 存储 0/1/2/3/9/10，
     * 前端按 [ldap, oidc, cas, oauth2, saml2][n-1] 取值，需映射为 0..5；所选方式未启用时返回 0。
     */
    public int defaultLogin() {
        try {
            String stored = settingManage.get("basic.defaultLogin", "0");
            String name;
            int category;
            switch (StringUtils.trim(stored)) {
                case "1" -> {
                    name = EeSsoConstants.LDAP;
                    category = 1;
                }
                case "2" -> {
                    name = EeSsoConstants.OIDC;
                    category = 2;
                }
                case "3" -> {
                    name = EeSsoConstants.CAS;
                    category = 3;
                }
                case "9", "4" -> {
                    name = EeSsoConstants.OAUTH2;
                    category = 4;
                }
                case "10", "5" -> {
                    name = EeSsoConstants.SAML2;
                    category = 5;
                }
                default -> {
                    return 0;
                }
            }
            return configManage.active(name) ? category : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String save(String name, Object vo, String validateResult) {
        if (vo == null) {
            DEException.throwException(Translator.get("ee_sso_config_incomplete"));
        }
        boolean valid = "true".equals(validateResult);
        EeAuthConfig before = configManage.find(name);
        Boolean enable = valid ? null : Boolean.FALSE;
        EeAuthConfig saved = configManage.save(name, vo, valid, enable);
        gatewaySupport.evict(name);
        boolean active = Boolean.TRUE.equals(saved.getEnable()) && valid;
        if (active || (before != null && Boolean.TRUE.equals(before.getEnable()))) {
            afterChange(name, active);
        }
        return String.valueOf(saved.getId());
    }

    private EeAuthConfig requireProvider(Long id) {
        EeAuthConfig entity = configManage.findById(id);
        if (entity == null || !EeSsoConstants.AUTH_PROVIDERS.contains(entity.getName())) {
            DEException.throwException(Translator.get("ee_sso_config_not_found"));
        }
        return entity;
    }

    /**
     * 启停或配置变化后的联动：同步 APISIX 路由、清除网关配置缓存
     */
    private void afterChange(String name, boolean enable) {
        gatewaySupport.evict(name);
        try {
            if (EeSsoConstants.OIDC.equals(name)) {
                apisixRouteManage.syncOidc(configManage.load(name, XpackOidcVO.class), enable);
            } else if (EeSsoConstants.CAS.equals(name)) {
                apisixRouteManage.syncCas(configManage.load(name, XpackCasVO.class), enable);
            }
        } catch (Exception e) {
            LogUtil.warn("sync gateway route failed: " + e.getMessage());
        }
    }
}
