package io.dataease.ee.license;

import io.dataease.license.bo.F2CLicResult;
import io.dataease.license.bo.F2CLicense;
import io.dataease.license.manage.F2CLicManage;
import io.dataease.license.utils.LicenseUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自研企业版“许可证”：不校验外部许可证，由配置 {@code dataease.ee.enabled} 决定企业功能是否启用。
 */
@Component("f2CLicManage")
public class EeLicManage implements F2CLicManage {

    public static final String EDITION = "Enterprise";

    @Value("${dataease.ee.enabled:true}")
    private boolean enabled;

    @Value("${dataease.ee.corporation:自研企业版}")
    private String corporation;

    @PostConstruct
    public void init() {
        LicenseUtil.set(current());
    }

    private F2CLicResult current() {
        if (!enabled) {
            return F2CLicResult.noRecord();
        }
        F2CLicense license = new F2CLicense();
        license.setCorporation(corporation);
        license.setEdition(EDITION);
        license.setExpired("永久");
        license.setCount(0L);
        license.setProduct("DataEase v3");
        license.setRemark("自研企业版，不限用户数");
        return F2CLicResult.valid(license);
    }

    @Override
    public F2CLicResult updateLicense(String product, String license) {
        return validate();
    }

    @Override
    public F2CLicResult validate(String product, String license) {
        return validate();
    }

    @Override
    public F2CLicResult validate() {
        F2CLicResult result = current();
        LicenseUtil.set(result);
        return result;
    }

    @Override
    public void revert() {
        // 自研企业版无外部许可证，无需还原
    }
}
