package io.dataease.ee.setting.apikey;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.constant.AuthConstant;
import io.dataease.ee.auth.EeRequestAuthenticator;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.apikey.dao.PerApiKey;
import io.dataease.exception.DEException;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * API Key 身份识别。
 *
 * <p>请求头 {@code X-DE-ASK-TOKEN: <JWT>}：JWT 使用 HS256、以 accessSecret 为密钥签名，载荷必须包含
 * {@code accessKey}，建议同时携带 {@code exp}（携带时校验过期）。以 API Key 所属用户及其当前组织身份访问。</p>
 */
@Component
@Order(30)
public class ApiKeyAuthenticator implements EeRequestAuthenticator {

    private final EeApiKeyManage apiKeyManage;

    private final EeOrgManage orgManage;

    public ApiKeyAuthenticator(EeApiKeyManage apiKeyManage, EeOrgManage orgManage) {
        this.apiKeyManage = apiKeyManage;
        this.orgManage = orgManage;
    }

    @Override
    public boolean supports(HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getHeader(AuthConstant.ASK_TOKEN_KEY));
    }

    @Override
    public void authenticate(HttpServletRequest request) {
        String token = request.getHeader(AuthConstant.ASK_TOKEN_KEY).trim();
        DecodedJWT decoded = null;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            fail("api key token is invalid");
        }
        String accessKey = decoded.getClaim("accessKey").asString();
        if (StringUtils.isBlank(accessKey)) {
            fail("api key token is invalid");
        }
        PerApiKey apiKey = apiKeyManage.findByAccessKey(accessKey).orElse(null);
        if (apiKey == null) {
            fail("api key is invalid");
        }
        if (!Boolean.TRUE.equals(apiKey.getEnable())) {
            fail("api key is disabled");
        }
        try {
            JWT.require(Algorithm.HMAC256(apiKey.getAccessSecret())).withClaim("accessKey", accessKey).build().verify(decoded);
        } catch (TokenExpiredException e) {
            fail("token is Expired");
        } catch (JWTVerificationException e) {
            fail("api key token is invalid");
        }
        V3BaseUser user = V3UserUtil.getUser(apiKey.getUid());
        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
            fail("user_disable");
        }
        V3UserUtil.setContext(apiKey.getUid(), orgManage.resolveCurrentOid(apiKey.getUid()));
        request.setAttribute(EeSettingConstants.AUTH_TYPE_ATTR, EeSettingConstants.AUTH_TYPE_API_KEY);
    }

    private void fail(String message) {
        DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), message);
    }
}
