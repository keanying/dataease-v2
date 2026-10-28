package io.dataease.ee.setting;

import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingRepository;
import io.dataease.system.manage.SysParameterManage;
import io.dataease.utils.EncryptUtils;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 企业版系统参数读写（core_sys_setting，pkey/pval）。
 *
 * <p>{@link #SENSITIVE_KEYS} 中的设置项（邮件密码、HMAC 密钥）以 {@code ENC:} 前缀加密存储，
 * 使用与数据源配置相同的安装级 AES 密钥（{@link EncryptUtils}），读取时自动解密；历史明文值启动时自动加密。</p>
 */
@Component
public class EeSettingManage {

    private final SysParameterManage sysParameterManage;

    private final CoreSysSettingRepository repository;

    public EeSettingManage(SysParameterManage sysParameterManage, CoreSysSettingRepository repository) {
        this.sysParameterManage = sysParameterManage;
        this.repository = repository;
    }

    public static final Set<String> SENSITIVE_KEYS = Set.of(EeSettingConstants.EMAIL_PREFIX + "pwd", EeSettingConstants.HMAC_SECRET);

    private static final String ENCRYPTED_PREFIX = "ENC:";

    public String get(String key, String defaultValue) {
        String value = reveal(key, sysParameterManage.singleVal(key));
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        return NumberUtils.toInt(get(key, null), defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : StringUtils.equalsIgnoreCase(value, "true");
    }

    /**
     * 按前缀查询设置项（按 sort 升序）
     */
    public List<CoreSysSetting> group(String prefix) {
        return sysParameterManage.groupList(prefix).stream()
                .sorted(Comparator.comparing(s -> s.getSort() == null ? 0 : s.getSort()))
                .map(s -> SENSITIVE_KEYS.contains(s.getPkey())
                        ? new CoreSysSetting(s.getId(), s.getPkey(), reveal(s.getPkey(), s.getPval()), s.getType(), s.getSort())
                        : s)
                .toList();
    }

    @Transactional
    public void put(String key, String value, String type, Integer sort) {
        repository.deleteByPkey(key);
        repository.flush();
        if (StringUtils.isEmpty(value)) {
            // 空值不落库（Oracle/达梦把空串当作 NULL，pval 非空约束会失败）；读取时空值与缺省等价
            return;
        }
        CoreSysSetting setting = new CoreSysSetting();
        setting.setId(IDUtils.snowID());
        setting.setPkey(key);
        setting.setPval(conceal(key, StringUtils.defaultString(value)));
        setting.setType(StringUtils.defaultIfBlank(type, "text"));
        setting.setSort(sort == null ? 0 : sort);
        repository.save(setting);
    }

    /**
     * 在一个事务内批量写入
     */
    @Transactional
    public void putAll(Collection<Item> items) {
        for (Item item : items) {
            put(item.key(), item.value(), item.type(), item.sort());
        }
    }

    @Transactional
    public void delete(String key) {
        repository.deleteByPkey(key);
    }

    /**
     * 启动时把历史明文保存的敏感设置项改为加密存储（EncryptUtils 的密钥由 ApplicationRunner 初始化，早于本事件）
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void encryptLegacy() {
        for (String key : SENSITIVE_KEYS) {
            try {
                repository.findByPkey(key).ifPresent(setting -> {
                    String value = setting.getPval();
                    if (StringUtils.isNotBlank(value) && !value.startsWith(ENCRYPTED_PREFIX)) {
                        setting.setPval(conceal(key, value));
                        repository.save(setting);
                    }
                });
            } catch (Exception e) {
                LogUtil.error("encrypt setting " + key + " failed: " + e.getMessage(), e);
            }
        }
    }

    private static String conceal(String key, String value) {
        if (!SENSITIVE_KEYS.contains(key) || StringUtils.isBlank(value) || value.startsWith(ENCRYPTED_PREFIX)) {
            return value;
        }
        return ENCRYPTED_PREFIX + EncryptUtils.aesEncrypt(value);
    }

    private static String reveal(String key, String value) {
        if (!SENSITIVE_KEYS.contains(key) || value == null || !value.startsWith(ENCRYPTED_PREFIX)) {
            return value;
        }
        Object plain = EncryptUtils.aesDecrypt(value.substring(ENCRYPTED_PREFIX.length()));
        return plain == null ? null : plain.toString();
    }

    /**
     * 设置项
     */
    public record Item(String key, String value, String type, Integer sort) {
    }
}
