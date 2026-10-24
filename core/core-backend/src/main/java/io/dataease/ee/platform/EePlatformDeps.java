package io.dataease.ee.platform;

import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeLoginTicketManage;
import io.dataease.ee.sso.support.EeSsoUserManage;
import io.dataease.ee.sso.support.EeTtlStore;
import org.springframework.stereotype.Component;

/**
 * 平台对接公共依赖
 */
@Component
public record EePlatformDeps(EeAuthConfigManage configManage, EeHttp http, EeTtlStore ttlStore, EeLoginTicketManage ticketManage,
                             EeSsoUserManage ssoUserManage) {
}
