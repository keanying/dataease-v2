package io.dataease.ee.sso.oauth2;

import io.dataease.api.xpack.settings.XpackOauth2Api;
import io.dataease.api.xpack.settings.request.XpackOauth2TokenRequest;
import io.dataease.api.xpack.settings.vo.XpackOauthAuthVO;
import io.dataease.api.xpack.settings.vo.XpackOauthTokenVO;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 登录入口（白名单 {@code /oauth2/}）
 */
@RestController("xpackOauth2Server")
@RequestMapping("/oauth2")
public class Oauth2Server implements XpackOauth2Api {

    private final EeOauth2Manage oauth2Manage;

    public Oauth2Server(EeOauth2Manage oauth2Manage) {
        this.oauth2Manage = oauth2Manage;
    }

    @Override
    public XpackOauthAuthVO auth() {
        return oauth2Manage.auth();
    }

    @Override
    public XpackOauthTokenVO oauth2Token(XpackOauth2TokenRequest request) {
        return oauth2Manage.token(request);
    }
}
