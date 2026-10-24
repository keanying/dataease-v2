package io.dataease.ee.sso.gateway;

import io.dataease.api.xpack.settings.vo.XpackCasVO;
import io.dataease.api.xpack.settings.vo.XpackOidcVO;
import io.dataease.ee.auth.EeExternalLoginProvider;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeSsoUserManage;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.ServletUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OIDC（origin=2）、CAS（origin=3）登录：身份来自网关头或内置网关会话
 */
@Configuration
public class GatewayLoginProviders {

    @Bean
    public EeExternalLoginProvider oidcLoginProvider(EeAuthConfigManage configManage, EeSsoGatewaySupport support, EeSsoUserManage userManage) {
        return new GatewayProvider(EeConstants.ORIGIN_OIDC, EeSsoConstants.OIDC, XpackOidcVO.class, configManage, support, userManage);
    }

    @Bean
    public EeExternalLoginProvider casLoginProvider(EeAuthConfigManage configManage, EeSsoGatewaySupport support, EeSsoUserManage userManage) {
        return new GatewayProvider(EeConstants.ORIGIN_CAS, EeSsoConstants.CAS, XpackCasVO.class, configManage, support, userManage);
    }

    record GatewayProvider(int origin, String name, Class<?> type, EeAuthConfigManage configManage, EeSsoGatewaySupport support,
                           EeSsoUserManage userManage) implements EeExternalLoginProvider {

        @Override
        public PerUser platformLogin() {
            configManage.requireActive(name, type);
            ExternalIdentity identity = support.resolve(origin, ServletUtils.request());
            if (identity == null) {
                DEException.throwException(Translator.get("ee_sso_gateway_identity_missing"));
            }
            return userManage.resolve(identity);
        }
    }
}
