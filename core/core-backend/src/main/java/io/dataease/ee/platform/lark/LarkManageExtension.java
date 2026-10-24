package io.dataease.ee.platform.lark;

import io.dataease.ee.interact.EeExtension;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 核心飞书数据源获取 tenant_access_token（{@code DataSourceManage.getTenantAccessToken}）
 */
@EeExtension("larkManage")
public class LarkManageExtension {

    private final EeLarkManage lark;

    private final EeLarkManage larksuite;

    public LarkManageExtension(@Qualifier("eeLarkManage") EeLarkManage lark, @Qualifier("eeLarksuiteManage") EeLarkManage larksuite) {
        this.lark = lark;
        this.larksuite = larksuite;
    }

    public String getTenantAccessToken() {
        String token = lark.tenantAccessToken();
        return token != null ? token : larksuite.tenantAccessToken();
    }
}
