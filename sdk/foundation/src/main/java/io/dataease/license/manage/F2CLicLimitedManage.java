package io.dataease.license.manage;

import java.util.List;

/**
 * 许可证配额限制契约。自研企业版默认不限制。
 */
public interface F2CLicLimitedManage {

    int getEmbeddedCount();

    List<String> getLimitedFlagList();

    int getDsCount();

    boolean limited();
}
