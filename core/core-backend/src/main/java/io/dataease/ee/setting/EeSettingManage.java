package io.dataease.ee.setting;

import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingRepository;
import io.dataease.system.manage.SysParameterManage;
import io.dataease.utils.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企业版系统参数读写（core_sys_setting，pkey/pval）。
 */
@Component
public class EeSettingManage {

    private final SysParameterManage sysParameterManage;

    private final CoreSysSettingRepository repository;

    public EeSettingManage(SysParameterManage sysParameterManage, CoreSysSettingRepository repository) {
        this.sysParameterManage = sysParameterManage;
        this.repository = repository;
    }

    public String get(String key, String defaultValue) {
        String value = sysParameterManage.singleVal(key);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        return NumberUtils.toInt(get(key, null), defaultValue);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : StringUtils.equalsIgnoreCase(value, "true");
    }

    @Transactional
    public void put(String key, String value, String type, Integer sort) {
        repository.deleteByPkey(key);
        CoreSysSetting setting = new CoreSysSetting();
        setting.setId(IDUtils.snowID());
        setting.setPkey(key);
        setting.setPval(StringUtils.defaultString(value));
        setting.setType(StringUtils.defaultIfBlank(type, "text"));
        setting.setSort(sort == null ? 0 : sort);
        repository.save(setting);
    }
}
