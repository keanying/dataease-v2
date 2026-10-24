package io.dataease.ee.sso.support;

import io.dataease.ee.auth.EeExternalLoginProvider;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.ServletUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 以临时登录票据（X-DE-TOKEN）换取登录 token 的来源：飞书(4)、钉钉(5)、企业微信(6)、国际飞书(7)、OAuth2(9)、SAML2(10)
 */
@Configuration
public class TicketLoginProviders {

    @Bean
    public EeExternalLoginProvider larkLoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_LARK, c, t, u);
    }

    @Bean
    public EeExternalLoginProvider dingtalkLoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_DINGTALK, c, t, u);
    }

    @Bean
    public EeExternalLoginProvider wecomLoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_WECOM, c, t, u);
    }

    @Bean
    public EeExternalLoginProvider larksuiteLoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_LARKSUITE, c, t, u);
    }

    @Bean
    public EeExternalLoginProvider oauth2LoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_OAUTH2, c, t, u);
    }

    @Bean
    public EeExternalLoginProvider saml2LoginProvider(EeAuthConfigManage c, EeLoginTicketManage t, EeSsoUserManage u) {
        return new TicketProvider(EeConstants.ORIGIN_SAML, c, t, u);
    }

    record TicketProvider(int origin, EeAuthConfigManage configManage, EeLoginTicketManage ticketManage,
                          EeSsoUserManage userManage) implements EeExternalLoginProvider {

        @Override
        public PerUser platformLogin() {
            if (!configManage.active(EeSsoConstants.nameOfOrigin(origin))) {
                DEException.throwException(Translator.get("ee_platform_not_enabled"));
            }
            return userManage.resolve(ticketManage.consume(origin, ServletUtils.getToken()));
        }
    }
}
