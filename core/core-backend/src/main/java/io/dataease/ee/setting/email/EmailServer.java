package io.dataease.ee.setting.email;

import io.dataease.api.email.EmailApi;
import io.dataease.api.system.vo.SettingItemVO;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 邮件设置（仅系统管理员）
 */
@RestController("emailServer")
@RequestMapping("/email")
public class EmailServer implements EmailApi {

    private static final Set<String> KEYS = Set.of(EeMailSender.HOST, EeMailSender.PORT, EeMailSender.ACCOUNT, EeMailSender.PWD,
            EeMailSender.FROM, EeMailSender.RECIPIENT, EeMailSender.SSL, EeMailSender.TLS);

    private final EeSettingManage settingManage;

    private final EeMailSender mailSender;

    public EmailServer(EeSettingManage settingManage, EeMailSender mailSender) {
        this.settingManage = settingManage;
        this.mailSender = mailSender;
    }

    @Override
    public List<SettingItemVO> queryEmailSetting() {
        EeSettingSupport.checkSysAdmin();
        List<SettingItemVO> result = new ArrayList<>();
        for (CoreSysSetting setting : settingManage.group(EeSettingConstants.EMAIL_PREFIX)) {
            SettingItemVO vo = new SettingItemVO();
            vo.setPkey(setting.getPkey());
            vo.setPval(setting.getPval());
            vo.setType(setting.getType());
            vo.setSort(setting.getSort());
            result.add(vo);
        }
        return result;
    }

    @Override
    public void saveEmailSetting(List<SettingItemVO> settingItemVOS) {
        EeSettingSupport.checkSysAdmin();
        Map<String, String> config = toConfig(settingItemVOS);
        validatePort(config);
        List<EeSettingManage.Item> items = new ArrayList<>();
        for (SettingItemVO vo : settingItemVOS) {
            if (!KEYS.contains(vo.getPkey())) {
                continue;
            }
            String value = StringUtils.defaultString(vo.getPval());
            if (StringUtils.equalsAny(vo.getPkey(), EeMailSender.SSL, EeMailSender.TLS)) {
                value = StringUtils.equalsIgnoreCase(value.trim(), "true") ? "true" : "false";
            } else if (!StringUtils.equals(vo.getPkey(), EeMailSender.PWD)) {
                value = value.trim();
            }
            items.add(new EeSettingManage.Item(vo.getPkey(), value, StringUtils.defaultIfBlank(vo.getType(), "text"), vo.getSort()));
        }
        settingManage.putAll(items);
    }

    @Override
    public void validate(List<SettingItemVO> settingItemVOS) {
        EeSettingSupport.checkSysAdmin();
        Map<String, String> config = toConfig(settingItemVOS);
        validatePort(config);
        String recipient = config.get(EeMailSender.RECIPIENT);
        if (StringUtils.isBlank(recipient)) {
            mailSender.testConnection(config);
            return;
        }
        mailSender.send(config, EeMailSender.splitAddress(recipient), Translator.get("ee_settings.email_test_subject"),
                Translator.get("ee_settings.email_test_content"), null);
    }

    private Map<String, String> toConfig(List<SettingItemVO> items) {
        Map<String, String> config = new HashMap<>();
        if (CollectionUtils.isNotEmpty(items)) {
            for (SettingItemVO vo : items) {
                if (vo != null && KEYS.contains(vo.getPkey())) {
                    config.put(vo.getPkey(), vo.getPval());
                }
            }
        }
        return config;
    }

    private void validatePort(Map<String, String> config) {
        String host = StringUtils.trimToNull(config.get(EeMailSender.HOST));
        String port = StringUtils.trimToEmpty(config.get(EeMailSender.PORT));
        int p = NumberUtils.toInt(port, -1);
        if (host == null || p <= 0 || p > 65535) {
            DEException.throwException(Translator.get("ee_settings.email_not_configured"));
        }
    }
}
