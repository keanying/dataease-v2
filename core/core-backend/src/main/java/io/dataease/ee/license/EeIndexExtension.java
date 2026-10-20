package io.dataease.ee.license;

import io.dataease.ee.interact.EeExtension;
import io.dataease.license.utils.LicenseUtil;

/**
 * 前端通过 {@code /xpackModel} 判断企业功能：返回 {@code true} 时启用全部企业界面。
 */
@EeExtension("deIndexManage")
public class EeIndexExtension {

    public Boolean xpackModel() {
        return LicenseUtil.licenseValid() ? Boolean.TRUE : null;
    }
}
