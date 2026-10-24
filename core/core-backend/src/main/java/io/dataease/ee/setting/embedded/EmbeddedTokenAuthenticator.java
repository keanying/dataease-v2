package io.dataease.ee.setting.embedded;

import io.dataease.constant.AuthConstant;
import io.dataease.ee.auth.EeRequestAuthenticator;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.exception.DEException;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 嵌入式 token（X-EMBEDDED-TOKEN）身份识别：优先于登录 token，低于公共链接 token。
 *
 * <p>跨站请求（{@code Sec-Fetch-Site} 不是 same-origin 且带 {@code Origin}）的来源必须在应用允许的域名内，
 * 可通过 {@code dataease.embedded.origin-check=false} 关闭。</p>
 */
@Component
@Order(20)
public class EmbeddedTokenAuthenticator implements EeRequestAuthenticator {

    private final EeEmbeddedManage embeddedManage;

    private final EeOrgManage orgManage;

    @Value("${dataease.embedded.origin-check:true}")
    private boolean originCheck;

    public EmbeddedTokenAuthenticator(EeEmbeddedManage embeddedManage, EeOrgManage orgManage) {
        this.embeddedManage = embeddedManage;
        this.orgManage = orgManage;
    }

    @Override
    public boolean supports(HttpServletRequest request) {
        String token = request.getHeader(AuthConstant.EMBEDDED_TOKEN_KEY);
        return StringUtils.isNotBlank(token) && !StringUtils.equalsAny(token.trim(), "null", "undefined");
    }

    @Override
    public void authenticate(HttpServletRequest request) {
        EeEmbeddedManage.Verified verified = embeddedManage.verify(request.getHeader(AuthConstant.EMBEDDED_TOKEN_KEY).trim());
        if (originCheck) {
            checkOrigin(request, verified);
        }
        V3UserUtil.setContext(verified.uid(), orgManage.resolveCurrentOid(verified.uid()));
        request.setAttribute(EeSettingConstants.AUTH_TYPE_ATTR, EeSettingConstants.AUTH_TYPE_EMBEDDED);
        request.setAttribute(EeSettingConstants.EMBEDDED_CLAIMS_ATTR, verified.claims());
    }

    private void checkOrigin(HttpServletRequest request, EeEmbeddedManage.Verified verified) {
        String origin = request.getHeader("Origin");
        if (StringUtils.isBlank(origin) || StringUtils.equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"), "same-origin")) {
            return;
        }
        String normalized = EeEmbeddedManage.normalizeOrigin(origin);
        if (normalized == null) {
            return;
        }
        String host = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (StringUtils.isNotBlank(host) && (StringUtils.equalsIgnoreCase(normalized, EeEmbeddedManage.normalizeOrigin("http://" + host))
                || StringUtils.equalsIgnoreCase(normalized, EeEmbeddedManage.normalizeOrigin("https://" + host)))) {
            return;
        }
        if (!embeddedManage.originAllowed(verified.app(), origin)) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "embedded origin is not allowed: " + normalized);
        }
    }
}
