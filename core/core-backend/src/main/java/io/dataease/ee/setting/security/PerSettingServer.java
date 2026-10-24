package io.dataease.ee.setting.security;

import io.dataease.api.permissions.setting.api.PerSettingApi;
import io.dataease.api.permissions.setting.vo.PerSettingItemVO;
import io.dataease.api.system.vo.SettingItemVO;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.manage.SysParameterManage;
import io.dataease.utils.JsonUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 安全设置：MFA、HMAC（/perSetting）
 */
@RestController("perSettingServer")
@RequestMapping("/perSetting")
public class PerSettingServer implements PerSettingApi {

    private static final List<Def> MFA_DEFS = List.of(
            new Def(EeSettingConstants.MFA_STATUS, "0", "text", 1),
            new Def(EeSettingConstants.MFA_PLATFORM_ENABLE, "false", "text", 2),
            new Def(EeSettingConstants.MFA_OTP_NAME, "DataEase", "text", 4),
            new Def(EeSettingConstants.MFA_RATE, "1", "text", 5));

    private static final List<Def> HMAC_DEFS = List.of(
            new Def(EeSettingConstants.HMAC_ENABLE, "false", "text", 1),
            new Def(EeSettingConstants.HMAC_SECRET, "", "pwd", 2),
            new Def(EeSettingConstants.HMAC_CLOCK_SKEW, String.valueOf(EeHmacManage.DEFAULT_CLOCK_SKEW), "text", 3));

    private final EeSettingManage settingManage;

    private final EeHmacManage hmacManage;

    private final SysParameterManage sysParameterManage;

    public PerSettingServer(EeSettingManage settingManage, EeHmacManage hmacManage, SysParameterManage sysParameterManage) {
        this.settingManage = settingManage;
        this.hmacManage = hmacManage;
        this.sysParameterManage = sysParameterManage;
    }

    @Override
    public List<PerSettingItemVO> basicSetting() {
        EeSettingSupport.checkSysAdmin();
        List<CoreSysSetting> list = new ArrayList<>(sysParameterManage.groupList("basic."));
        return sysParameterManage.convert(list).stream().map(vo -> item(vo.getPkey(), vo.getPval(), vo.getType(), vo.getSort())).toList();
    }

    @Override
    public void saveBasic(List<Object> settings) {
        EeSettingSupport.checkSysAdmin();
        if (CollectionUtils.isEmpty(settings)) {
            return;
        }
        List<SettingItemVO> vos = settings.stream()
                .map(o -> JsonUtil.parseObject(String.valueOf(JsonUtil.toJSONString(o)), SettingItemVO.class))
                .filter(Objects::nonNull)
                .filter(vo -> StringUtils.startsWith(vo.getPkey(), "basic."))
                .toList();
        sysParameterManage.saveBasic(vos);
    }

    @Override
    public String singleValue(String key) {
        // 非管理员只能读取基础设置，避免泄露邮件密码、HMAC 密钥等
        if (!V3UserUtil.isSysAdmin() && !StringUtils.startsWith(key, "basic.")) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return settingManage.get(key, null);
    }

    @Override
    public List<PerSettingItemVO> mfaSetting() {
        EeSettingSupport.checkSysAdmin();
        return query(EeSettingConstants.MFA_PREFIX, MFA_DEFS);
    }

    @Override
    public void saveMfa(List<PerSettingItemVO> settings) {
        EeSettingSupport.checkSysAdmin();
        Map<String, PerSettingItemVO> map = toMap(settings, MFA_DEFS);
        String status = value(map, EeSettingConstants.MFA_STATUS, "0");
        if (!StringUtils.equalsAny(status, "0", "1", "2")) {
            DEException.throwException(Translator.get("ee_settings.invalid_param", EeSettingConstants.MFA_STATUS));
        }
        int rate = NumberUtils.toInt(value(map, EeSettingConstants.MFA_RATE, "1"), -1);
        if (rate < 1 || rate > 8) {
            DEException.throwException(Translator.get("ee_settings.mfa_rate_invalid"));
        }
        String otpName = value(map, EeSettingConstants.MFA_OTP_NAME, "DataEase");
        if (otpName.length() > 64) {
            DEException.throwException(Translator.get("ee_settings.invalid_param", EeSettingConstants.MFA_OTP_NAME));
        }
        String platformEnable = StringUtils.equalsIgnoreCase(value(map, EeSettingConstants.MFA_PLATFORM_ENABLE, "false"), "true") ? "true" : "false";
        settingManage.putAll(List.of(
                item(map, EeSettingConstants.MFA_STATUS, status),
                item(map, EeSettingConstants.MFA_PLATFORM_ENABLE, platformEnable),
                item(map, EeSettingConstants.MFA_OTP_NAME, otpName),
                item(map, EeSettingConstants.MFA_RATE, String.valueOf(rate))));
    }

    @Override
    public Integer mfaStatus() {
        return settingManage.getInt(EeSettingConstants.MFA_STATUS, 0);
    }

    @Override
    public List<PerSettingItemVO> hmacSetting() {
        EeSettingSupport.checkSysAdmin();
        return query(EeSettingConstants.HMAC_PREFIX, HMAC_DEFS);
    }

    @Override
    public void saveHmac(List<PerSettingItemVO> settings) {
        EeSettingSupport.checkSysAdmin();
        Map<String, PerSettingItemVO> map = toMap(settings, HMAC_DEFS);
        boolean enable = StringUtils.equalsIgnoreCase(value(map, EeSettingConstants.HMAC_ENABLE, "false"), "true");
        String secret = value(map, EeSettingConstants.HMAC_SECRET, "");
        int skew = NumberUtils.toInt(value(map, EeSettingConstants.HMAC_CLOCK_SKEW, String.valueOf(EeHmacManage.DEFAULT_CLOCK_SKEW)), -1);
        if (skew < 1 || skew > 600) {
            DEException.throwException(Translator.get("ee_settings.hmac_clock_skew_invalid"));
        }
        if (enable && StringUtils.isBlank(secret)) {
            DEException.throwException(Translator.get("ee_settings.hmac_secret_required"));
        }
        if (secret.length() > 255) {
            DEException.throwException(Translator.get("ee_settings.invalid_param", EeSettingConstants.HMAC_SECRET));
        }
        settingManage.putAll(List.of(
                item(map, EeSettingConstants.HMAC_ENABLE, String.valueOf(enable)),
                item(map, EeSettingConstants.HMAC_SECRET, secret),
                item(map, EeSettingConstants.HMAC_CLOCK_SKEW, String.valueOf(skew))));
        hmacManage.evict();
    }

    @Override
    public String hmacInfo() {
        return hmacManage.encodeInfo();
    }

    @Override
    public String refreshHmacSecret() {
        EeSettingSupport.checkSysAdmin();
        return hmacManage.newSecret();
    }

    private List<PerSettingItemVO> query(String prefix, List<Def> defs) {
        Map<String, CoreSysSetting> stored = new HashMap<>();
        for (CoreSysSetting setting : settingManage.group(prefix)) {
            stored.put(setting.getPkey(), setting);
        }
        List<PerSettingItemVO> result = new ArrayList<>();
        for (Def def : defs) {
            CoreSysSetting setting = stored.get(def.key());
            if (setting == null) {
                result.add(item(def.key(), def.value(), def.type(), def.sort()));
            } else {
                result.add(item(setting.getPkey(), setting.getPval(), setting.getType(), setting.getSort()));
            }
        }
        return result;
    }

    private Map<String, PerSettingItemVO> toMap(List<PerSettingItemVO> settings, List<Def> defs) {
        Map<String, PerSettingItemVO> map = new HashMap<>();
        for (Def def : defs) {
            map.put(def.key(), item(def.key(), null, def.type(), def.sort()));
        }
        if (settings != null) {
            for (PerSettingItemVO vo : settings) {
                if (vo != null && map.containsKey(vo.getPkey())) {
                    PerSettingItemVO target = map.get(vo.getPkey());
                    target.setPval(vo.getPval());
                    if (StringUtils.isNotBlank(vo.getType())) {
                        target.setType(vo.getType());
                    }
                    if (vo.getSort() != null) {
                        target.setSort(vo.getSort());
                    }
                }
            }
        }
        return map;
    }

    private String value(Map<String, PerSettingItemVO> map, String key, String defaultValue) {
        PerSettingItemVO vo = map.get(key);
        return vo == null || vo.getPval() == null ? defaultValue : vo.getPval().trim();
    }

    private EeSettingManage.Item item(Map<String, PerSettingItemVO> map, String key, String value) {
        PerSettingItemVO vo = map.get(key);
        return new EeSettingManage.Item(key, value, vo.getType(), vo.getSort());
    }

    private PerSettingItemVO item(String key, String value, String type, Integer sort) {
        PerSettingItemVO vo = new PerSettingItemVO();
        vo.setPkey(key);
        vo.setPval(value);
        vo.setType(type);
        vo.setSort(sort);
        return vo;
    }

    private record Def(String key, String value, String type, int sort) {
    }
}
