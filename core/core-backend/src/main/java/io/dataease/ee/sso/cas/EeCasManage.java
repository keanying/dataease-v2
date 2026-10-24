package io.dataease.ee.sso.cas;

import io.dataease.api.xpack.settings.vo.XpackCasVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeXml;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CAS：配置校验，以及内置网关模式下的登录跳转与 ticket 校验（CAS 3.0 /p3/serviceValidate，回退 2.0 /serviceValidate）。
 */
@Component
public class EeCasManage {

    private static final String CAS_NS = "http://www.yale.edu/tp/cas";

    private final EeHttp http;

    public EeCasManage(EeHttp http) {
        this.http = http;
    }

    public String validate(XpackCasVO vo) {
        try {
            if (vo == null || StringUtils.isBlank(vo.getIdpUri())) {
                return Translator.get("ee_sso_config_incomplete");
            }
            EeHttp.Response response = http.get(idp(vo) + "/login", null);
            if (response.status() >= 500 || response.status() == 404) {
                return "CAS login page HTTP " + response.status();
            }
            return "true";
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    public String loginUrl(XpackCasVO vo, String service) {
        return EeHttp.url(idp(vo) + "/login", Map.of("service", service));
    }

    public String logoutUrl(XpackCasVO vo) {
        Map<String, String> query = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(vo.getLogoutRedirectUrl())) {
            query.put("service", vo.getLogoutRedirectUrl().trim());
        }
        return EeHttp.url(idp(vo) + "/logout", query);
    }

    /**
     * 校验 ticket，返回用户；失败抛出异常
     */
    public ExternalIdentity validateTicket(XpackCasVO vo, String ticket, String service) {
        Map<String, String> query = Map.of("ticket", ticket, "service", service);
        EeHttp.Response response = http.get(EeHttp.url(idp(vo) + "/p3/serviceValidate", query), null);
        if (!response.ok() || !StringUtils.contains(response.body(), "serviceResponse")) {
            response = http.get(EeHttp.url(idp(vo) + "/serviceValidate", query), null).require("CAS serviceValidate");
        }
        Document doc = EeXml.parse(response.body());
        NodeList success = doc.getElementsByTagNameNS(CAS_NS, "authenticationSuccess");
        if (success.getLength() != 1) {
            NodeList failure = doc.getElementsByTagNameNS(CAS_NS, "authenticationFailure");
            String reason = failure.getLength() > 0 ? StringUtils.trim(failure.item(0).getTextContent()) : "invalid ticket";
            throw new IllegalStateException("CAS: " + reason);
        }
        Element successEl = (Element) success.item(0);
        NodeList users = successEl.getElementsByTagNameNS(CAS_NS, "user");
        String user = users.getLength() > 0 ? StringUtils.trimToNull(users.item(0).getTextContent()) : null;
        if (user == null) {
            throw new IllegalStateException("CAS: no user");
        }
        Map<String, String> attributes = new HashMap<>();
        NodeList attrs = successEl.getElementsByTagNameNS(CAS_NS, "attributes");
        if (attrs.getLength() > 0) {
            NodeList children = attrs.item(0).getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && StringUtils.isNotBlank(child.getTextContent())) {
                    attributes.putIfAbsent(child.getLocalName(), child.getTextContent().trim());
                }
            }
        }
        return identity(user, attributes);
    }

    public ExternalIdentity identity(String user, Map<String, String> attributes) {
        String name = firstNonBlank(attributes, "name", "displayName", "cn", "nickname");
        String email = firstNonBlank(attributes, "email", "mail");
        String phone = firstNonBlank(attributes, "phone", "mobile");
        return new ExternalIdentity(EeConstants.ORIGIN_CAS, user, user, name, email, phone);
    }

    private String firstNonBlank(Map<String, String> attributes, String... keys) {
        if (attributes == null) {
            return null;
        }
        for (String key : keys) {
            if (StringUtils.isNotBlank(attributes.get(key))) {
                return attributes.get(key);
            }
        }
        return null;
    }

    private String idp(XpackCasVO vo) {
        return StringUtils.removeEnd(vo.getIdpUri().trim(), "/");
    }
}
