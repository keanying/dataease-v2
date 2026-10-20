package io.dataease.license.manage;

import io.dataease.license.bo.F2CLicResult;

/**
 * 许可证管理契约，由 core-backend 中的 {@code io.dataease.ee} 提供实现。
 */
public interface F2CLicManage {

    F2CLicResult updateLicense(String product, String license);

    F2CLicResult validate(String product, String license);

    F2CLicResult validate();

    void revert();
}
