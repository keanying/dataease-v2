package io.dataease.ee.setting;

import io.dataease.api.system.vo.SettingItemVO;
import io.dataease.api.xpack.settings.XpackAuthenticationApi;
import io.dataease.api.xpack.settings.vo.XpackAuthenticationStatusVO;
import io.dataease.constant.XpackSettingConstants;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.setting.appearance.EeAppearanceManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.utils.LogUtil;
import io.dataease.utils.SystemSettingUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;

/**
 * 系统参数扩展（对应核心 {@code @XpackInteract("perSetting")}）：
 * <ul>
 *     <li>{@code SysParameterManage.convert}：为缺失的企业版基础设置项补默认行；</li>
 *     <li>{@code SysParameterManage.saveBasic}（后置）：保存企业版基础设置项（核心 saveGroup 会丢弃它们）；</li>
 *     <li>{@code SysParameterManage.getUiList}：返回外观设置（不含 community 项）；</li>
 *     <li>{@code SysParameterManage.defaultLogin}：默认登录方式 0/1/2/3/9/10 映射为前端的 0..5；</li>
 *     <li>{@code ExportCenterManage.singleValue}：读取设置值（如嵌入式导出模式）。</li>
 * </ul>
 */
@EeExtension("perSetting")
public class PerSettingExtension {

    /**
     * 企业版基础设置项默认值与顺序
     */
    private static final Map<String, Default> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(XpackSettingConstants.LOG_LIVE_TIME, new Default("30", 30));
        DEFAULTS.put(XpackSettingConstants.THRESHOLD_LOG_LIVE_TIME, new Default("30", 31));
        DEFAULTS.put(XpackSettingConstants.DATA_FILLING_LOG_LIVE_TIME, new Default("30", 32));
        DEFAULTS.put(XpackSettingConstants.THRESHOLD_LIMIT, new Default("10", 33));
        DEFAULTS.put(XpackSettingConstants.SPREADSHEET_QUERY_LIMIT, new Default(String.valueOf(XpackSettingConstants.DEFAULT_SPREADSHEET_QUERY_LIMIT), 34));
        DEFAULTS.put(XpackSettingConstants.EMBEDDED_EXPORT_MODE, new Default("sync", 35));
        DEFAULTS.put(XpackSettingConstants.DIP, new Default("false", 40));
        DEFAULTS.put(XpackSettingConstants.PVP, new Default("0", 41));
        DEFAULTS.put(XpackSettingConstants.DEFAULT_LOGIN, new Default("0", 42));
        DEFAULTS.put(XpackSettingConstants.LOGIN_LIMIT, new Default("false", 43));
        DEFAULTS.put(XpackSettingConstants.LOGIN_LIMIT_RATE, new Default("5", 44));
        DEFAULTS.put(XpackSettingConstants.LOGIN_LIMIT_TIME, new Default("10", 45));
        DEFAULTS.put(XpackSettingConstants.AUTO_CREATE_USER, new Default("false", 50));
        DEFAULTS.put(XpackSettingConstants.PLATFORM_OID, new Default("1", 51));
        DEFAULTS.put(XpackSettingConstants.PLATFORM_RID, new Default("", 52));
    }

    /**
     * 存储的默认登录方式 -> 前端序号（ldap, oidc, cas, oauth2, saml2）
     */
    private static final Map<String, Integer> LOGIN_MAPPING = Map.of("1", 1, "2", 2, "3", 3, "9", 4, "10", 5);

    private static final String[] LOGIN_NAMES = {null, "ldap", "oidc", "cas", "oauth2", "saml2"};

    private final EeSettingManage settingManage;

    private final EeAppearanceManage appearanceManage;

    private final ObjectProvider<XpackAuthenticationApi> authenticationApi;

    public PerSettingExtension(EeSettingManage settingManage, EeAppearanceManage appearanceManage,
                               ObjectProvider<XpackAuthenticationApi> authenticationApi) {
        this.settingManage = settingManage;
        this.appearanceManage = appearanceManage;
        this.authenticationApi = authenticationApi;
    }

    /**
     * 前置扩展：向待转换的列表补充缺失的企业版设置项（核心随后按 sort 排序并转换）
     */
    public void convert(List<CoreSysSetting> sysSettings) {
        if (sysSettings == null) {
            return;
        }
        boolean basicGroup = sysSettings.isEmpty() || sysSettings.stream()
                .allMatch(s -> StringUtils.startsWith(s.getPkey(), "basic."));
        if (!basicGroup) {
            return;
        }
        Set<String> exists = new HashSet<>();
        sysSettings.forEach(s -> exists.add(s.getPkey()));
        List<CoreSysSetting> missing = new ArrayList<>();
        DEFAULTS.forEach((key, def) -> {
            if (!exists.contains(key)) {
                missing.add(new CoreSysSetting(null, key, def.value(), "text", def.sort()));
            }
        });
        if (missing.isEmpty()) {
            return;
        }
        try {
            sysSettings.addAll(missing);
        } catch (UnsupportedOperationException e) {
            LogUtil.warn("basic setting list is immutable, x-pack defaults skipped");
        }
    }

    /**
     * 后置扩展：保存企业版基础设置项
     */
    public void saveBasic(List<SettingItemVO> vos) {
        if (CollectionUtils.isEmpty(vos)) {
            return;
        }
        List<EeSettingManage.Item> items = new ArrayList<>();
        for (SettingItemVO vo : vos) {
            String key = vo.getPkey();
            if (!SystemSettingUtils.xpackSetting(key)) {
                continue;
            }
            String value = normalize(key, StringUtils.trimToEmpty(vo.getPval()));
            Default def = DEFAULTS.get(key);
            Integer sort = vo.getSort() != null ? vo.getSort() : def == null ? 0 : def.sort();
            items.add(new EeSettingManage.Item(key, value, StringUtils.defaultIfBlank(vo.getType(), "text"), sort));
        }
        if (!items.isEmpty()) {
            settingManage.putAll(items);
        }
    }

    private String normalize(String key, String value) {
        switch (key) {
            case XpackSettingConstants.LOG_LIVE_TIME, XpackSettingConstants.THRESHOLD_LOG_LIVE_TIME,
                 XpackSettingConstants.DATA_FILLING_LOG_LIVE_TIME -> checkInt(key, value, 1, 4000);
            case XpackSettingConstants.THRESHOLD_LIMIT -> checkInt(key, value, 1, 50);
            case XpackSettingConstants.SPREADSHEET_QUERY_LIMIT, XpackSettingConstants.LOGIN_LIMIT_RATE,
                 XpackSettingConstants.LOGIN_LIMIT_TIME -> checkInt(key, value, 1, Integer.MAX_VALUE);
            case XpackSettingConstants.PVP -> checkInt(key, value, 0, 4);
            case XpackSettingConstants.DEFAULT_LOGIN -> {
                if (!StringUtils.equals(value, "0") && !LOGIN_MAPPING.containsKey(value)) {
                    invalid(key);
                }
            }
            case XpackSettingConstants.EMBEDDED_EXPORT_MODE -> {
                if (!StringUtils.equalsAny(value, "sync", "async")) {
                    invalid(key);
                }
            }
            case XpackSettingConstants.DIP, XpackSettingConstants.LOGIN_LIMIT, XpackSettingConstants.AUTO_CREATE_USER -> {
                return StringUtils.equalsIgnoreCase(value, "true") ? "true" : "false";
            }
            case XpackSettingConstants.PLATFORM_OID -> {
                if (StringUtils.isNotBlank(value) && !NumberUtils.isDigits(value)) {
                    invalid(key);
                }
            }
            case XpackSettingConstants.PLATFORM_RID -> {
                for (String rid : StringUtils.split(value, ',')) {
                    if (!NumberUtils.isDigits(rid.trim())) {
                        invalid(key);
                    }
                }
            }
            default -> {
            }
        }
        return value;
    }

    private void checkInt(String key, String value, int min, int max) {
        if (!NumberUtils.isDigits(value)) {
            invalid(key);
        }
        long v = NumberUtils.toLong(value, -1);
        if (v < min || v > max) {
            invalid(key);
        }
    }

    private void invalid(String key) {
        DEException.throwException(Translator.get("ee_settings.basic_value_invalid", key));
    }

    /**
     * 替换：外观设置列表（不含 community 项，前端据此识别企业版）
     */
    public List<Object> getUiList() {
        return appearanceManage.uiList();
    }

    /**
     * 替换：默认登录方式。存储值 0/1/2/3/9/10 → 0..5；所选方式未启用时返回 0
     */
    public Integer defaultLogin() {
        String stored = settingManage.get(XpackSettingConstants.DEFAULT_LOGIN, "0");
        Integer category = LOGIN_MAPPING.get(stored.trim());
        if (category == null) {
            return 0;
        }
        return enabled(LOGIN_NAMES[category]) ? category : 0;
    }

    private boolean enabled(String name) {
        XpackAuthenticationApi api = authenticationApi.getIfAvailable();
        if (api == null) {
            return false;
        }
        try {
            List<XpackAuthenticationStatusVO> status = api.status();
            if (status == null) {
                return false;
            }
            return status.stream().anyMatch(s -> s.isEnable() && (StringUtils.equalsIgnoreCase(s.getName(), name)
                    || StringUtils.equals(name, "saml2") && StringUtils.equalsIgnoreCase(s.getName(), "saml")));
        } catch (Exception e) {
            LogUtil.warn("query authentication status failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 替换：导出中心读取设置值（嵌入式导出模式默认 sync）
     */
    public String singleValue(String key) {
        String defaultValue = StringUtils.equals(key, XpackSettingConstants.EMBEDDED_EXPORT_MODE) ? "sync" : null;
        return settingManage.get(key, defaultValue);
    }

    private record Default(String value, int sort) {
    }
}
