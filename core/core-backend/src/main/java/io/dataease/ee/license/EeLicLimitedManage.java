package io.dataease.ee.license;

import io.dataease.license.manage.F2CLicLimitedManage;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 自研企业版不做配额限制。
 */
@Component("f2CLicLimitedManage")
public class EeLicLimitedManage implements F2CLicLimitedManage {

    @Override
    public int getEmbeddedCount() {
        return Integer.MAX_VALUE;
    }

    @Override
    public List<String> getLimitedFlagList() {
        return Collections.emptyList();
    }

    @Override
    public int getDsCount() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean limited() {
        return false;
    }
}
