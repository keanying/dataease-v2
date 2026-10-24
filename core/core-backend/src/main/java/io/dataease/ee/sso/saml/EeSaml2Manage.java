package io.dataease.ee.sso.saml;

import io.dataease.api.xpack.settings.vo.XpackSaml2VO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * SAML 2.0 SP：解析 IdP 元数据、生成 SP 元数据、发起 AuthnRequest（HTTP-Redirect / HTTP-POST）、校验 ACS 收到的 Response。
 *
 * <p>Response 必须带有效签名（Response 或 Assertion 至少一处，开启 wantAssertionsSigned 时须 Assertion 签名或整个 Response 签名），
 * 并校验 Destination、InResponseTo、Issuer、有效期、Audience 与 Recipient。暂不支持加密断言。</p>
 */
@Component
public class EeSaml2Manage {

    static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";

    static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";

    static final String NS_METADATA = "urn:oasis:names:tc:SAML:2.0:metadata";

    static final String NS_DSIG = "http://www.w3.org/2000/09/xmldsig#";

    static final String BINDING_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";

    static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    private static final long SKEW_SECONDS = 120;

    private static final long REQUEST_TTL_MINUTES = 10;

    @Value("${dataease.ee.sso.saml.allow-idp-initiated:false}")
    private boolean allowIdpInitiated;

    private final EeAuthConfigManage configManage;

    private final EeHttp http;

    private final EeTtlStore ttlStore;

    private final EeLoginTicketManage ticketManage;

    public EeSaml2Manage(EeAuthConfigManage configManage, EeHttp http, EeTtlStore ttlStore, EeLoginTicketManage ticketManage) {
        this.configManage = configManage;
        this.http = http;
        this.ttlStore = ttlStore;
        this.ticketManage = ticketManage;
    }

    /**
     * 保存前补全：解析 IdP 元数据，校验密钥
     */
    public EeSaml2Config prepare(XpackSaml2VO vo) {
        if (vo == null || StringUtils.isAnyBlank(vo.getIdpMetaUrl(), vo.getSpEntityId(), vo.getSpAcs())) {
            throw new IllegalStateException(Translator.get("ee_sso_config_incomplete"));
        }
        EeJson.mapping(vo.getMapping());
        EeSaml2Config config = EeJson.convert(vo, EeSaml2Config.class);
        config.setPrivateKey(vo.getPrivateKey());
        config.setCertificate(vo.getCertificate());
        if (vo.isWantAuthnRequestsSigned() || StringUtils.isNotBlank(vo.getPrivateKey()) || StringUtils.isNotBlank(vo.getCertificate())) {
            PrivateKey key = EeSamlCrypto.privateKey(vo.getPrivateKey());
            X509Certificate cert = EeSamlCrypto.certificate(vo.getCertificate());
            if (vo.isWantAuthnRequestsSigned() && (key == null || cert == null)) {
                throw new IllegalStateException(Translator.get("ee_saml_key_required"));
            }
            if (key != null && cert != null) {
                EeSamlCrypto.checkPair(key, cert);
            }
        }
        String xml = http.get(vo.getIdpMetaUrl().trim(), null).require("SAML metadata").body();
        IdpMetadata metadata = parseMetadata(xml);
        config.setIdpEntityId(metadata.entityId);
        config.setIdpSsoUrl(metadata.ssoUrl);
        config.setIdpLogoutUrl(metadata.logoutUrl);
        config.setIdpSsoBinding(metadata.ssoBinding);
        config.setIdpCertificates(metadata.certificates);
        if (config.getAssertionValidityTime() <= 0) {
            config.setAssertionValidityTime(300);
        }
        return config;
    }

    public String validate(XpackSaml2VO vo) {
        try {
            prepare(vo);
            return "true";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    IdpMetadata parseMetadata(String xml) {
        Document doc = EeXml.parse(xml);
        NodeList descriptors = doc.getElementsByTagNameNS(NS_METADATA, "IDPSSODescriptor");
        if (descriptors.getLength() == 0) {
            throw new IllegalArgumentException("IDPSSODescriptor not found in IdP metadata");
        }
        Element idp = (Element) descriptors.item(0);
        Element entity = (Element) idp.getParentNode();
        IdpMetadata metadata = new IdpMetadata();
        metadata.entityId = entity.getAttribute("entityID");
        NodeList sso = idp.getElementsByTagNameNS(NS_METADATA, "SingleSignOnService");
        for (int i = 0; i < sso.getLength(); i++) {
            Element item = (Element) sso.item(i);
            if (BINDING_REDIRECT.equals(item.getAttribute("Binding"))) {
                metadata.ssoUrl = item.getAttribute("Location");
                metadata.ssoBinding = BINDING_REDIRECT;
                break;
            }
            if (BINDING_POST.equals(item.getAttribute("Binding")) && metadata.ssoUrl == null) {
                metadata.ssoUrl = item.getAttribute("Location");
                metadata.ssoBinding = BINDING_POST;
            }
        }
        NodeList slo = idp.getElementsByTagNameNS(NS_METADATA, "SingleLogoutService");
        if (slo.getLength() > 0) {
            metadata.logoutUrl = ((Element) slo.item(0)).getAttribute("Location");
        }
        NodeList keys = idp.getElementsByTagNameNS(NS_METADATA, "KeyDescriptor");
        for (int i = 0; i < keys.getLength(); i++) {
            Element key = (Element) keys.item(i);
            String use = key.getAttribute("use");
            if (StringUtils.isNotBlank(use) && !"signing".equals(use)) {
                continue;
            }
            NodeList certs = key.getElementsByTagNameNS(NS_DSIG, "X509Certificate");
            for (int j = 0; j < certs.getLength(); j++) {
                String value = certs.item(j).getTextContent().replaceAll("\\s", "");
                EeSamlCrypto.certificate(value);
                metadata.certificates.add(value);
            }
        }
        if (StringUtils.isAnyBlank(metadata.entityId, metadata.ssoUrl)) {
            throw new IllegalArgumentException("IdP entityID or SingleSignOnService missing in metadata");
        }
        if (metadata.certificates.isEmpty()) {
            throw new IllegalArgumentException("IdP signing certificate missing in metadata");
        }
        return metadata;
    }

    public EeSaml2Config active() {
        EeSaml2Config config = configManage.requireActive(EeSsoConstants.SAML2, EeSaml2Config.class);
        if (StringUtils.isBlank(config.getIdpSsoUrl())) {
            DEException.throwException(Translator.get("ee_platform_not_enabled"));
        }
        return config;
    }

    /**
     * SP 元数据
     */
    public String metadata() {
        EeSaml2Config config = configManage.load(EeSsoConstants.SAML2, EeSaml2Config.class);
        if (config == null || StringUtils.isAnyBlank(config.getSpEntityId(), config.getSpAcs())) {
            DEException.throwException(Translator.get("ee_platform_not_enabled"));
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<md:EntityDescriptor xmlns:md=\"").append(NS_METADATA).append("\" entityID=\"").append(EeXml.escape(config.getSpEntityId())).append("\">");
        xml.append("<md:SPSSODescriptor AuthnRequestsSigned=\"").append(config.isWantAuthnRequestsSigned())
                .append("\" WantAssertionsSigned=\"").append(config.isWantAssertionsSigned())
                .append("\" protocolSupportEnumeration=\"").append(NS_PROTOCOL).append("\">");
        X509Certificate cert = safeCertificate(config.getCertificate());
        if (cert != null) {
            try {
                String encoded = Base64.getEncoder().encodeToString(cert.getEncoded());
                xml.append("<md:KeyDescriptor use=\"signing\"><ds:KeyInfo xmlns:ds=\"").append(NS_DSIG)
                        .append("\"><ds:X509Data><ds:X509Certificate>").append(encoded)
                        .append("</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>");
            } catch (Exception ignored) {
                // 忽略无法编码的证书
            }
        }
        xml.append("<md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified</md:NameIDFormat>");
        xml.append("<md:AssertionConsumerService Binding=\"").append(BINDING_POST).append("\" Location=\"")
                .append(EeXml.escape(config.getSpAcs())).append("\" index=\"0\" isDefault=\"true\"/>");
        xml.append("</md:SPSSODescriptor></md:EntityDescriptor>");
        return xml.toString();
    }

    /**
     * 发起认证：返回 Redirect 绑定的跳转地址，或 POST 绑定的自动提交页面
     */
    public LoginRequest login() {
        EeSaml2Config config = active();
        String id = "_" + EeSsoKeys.randomHex(20);
        ttlStore.put(EeSsoConstants.CACHE_SAML_REQUEST, id, "1", REQUEST_TTL_MINUTES);
        String xml = "<samlp:AuthnRequest xmlns:samlp=\"" + NS_PROTOCOL + "\" xmlns:saml=\"" + NS_ASSERTION + "\" ID=\"" + id
                + "\" Version=\"2.0\" IssueInstant=\"" + Instant.now().toString() + "\" Destination=\"" + EeXml.escape(config.getIdpSsoUrl())
                + "\" ProtocolBinding=\"" + BINDING_POST + "\" AssertionConsumerServiceURL=\"" + EeXml.escape(config.getSpAcs()) + "\">"
                + "<saml:Issuer>" + EeXml.escape(config.getSpEntityId()) + "</saml:Issuer>"
                + "<samlp:NameIDPolicy Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified\" AllowCreate=\"true\"/>"
                + "</samlp:AuthnRequest>";
        String relayState = "saml2";
        PrivateKey key = config.isWantAuthnRequestsSigned() ? EeSamlCrypto.privateKey(config.getPrivateKey()) : null;
        if (EeSaml2Manage.BINDING_POST.equals(config.getIdpSsoBinding())) {
            if (key != null) {
                xml = EeSamlSigner.signEnveloped(xml, id, key, EeSamlCrypto.certificate(config.getCertificate()));
            }
            String encoded = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
            String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body onload=\"document.forms[0].submit()\">"
                    + "<form method=\"post\" action=\"" + EeXml.escape(config.getIdpSsoUrl()) + "\">"
                    + "<input type=\"hidden\" name=\"SAMLRequest\" value=\"" + encoded + "\"/>"
                    + "<input type=\"hidden\" name=\"RelayState\" value=\"" + relayState + "\"/>"
                    + "<noscript><button type=\"submit\">Continue</button></noscript></form></body></html>";
            return new LoginRequest(null, html);
        }
        String query = "SAMLRequest=" + EeHttp.encode(deflate(xml)) + "&RelayState=" + EeHttp.encode(relayState);
        if (key != null) {
            query += "&SigAlg=" + EeHttp.encode(EeSamlCrypto.SIG_ALG_RSA_SHA256);
            query += "&Signature=" + EeHttp.encode(Base64.getEncoder().encodeToString(EeSamlCrypto.signRsaSha256(key, query)));
        }
        String url = config.getIdpSsoUrl() + (config.getIdpSsoUrl().contains("?") ? "&" : "?") + query;
        return new LoginRequest(url, null);
    }

    /**
     * 处理 ACS 收到的 SAMLResponse，返回前端回跳地址（携带临时登录票据）
     */
    public String acs(String samlResponse) {
        EeSaml2Config config = active();
        if (StringUtils.isBlank(samlResponse)) {
            throw new IllegalStateException("SAMLResponse is empty");
        }
        String xml = new String(Base64.getMimeDecoder().decode(samlResponse), StandardCharsets.UTF_8);
        ExternalIdentity identity = parseResponse(config, xml, Instant.now());
        String ticket = ticketManage.issue(identity);
        String base = StringUtils.removeEnd(config.getSpAcs(), "saml/sso");
        return base + "?state=fit2cloud-saml2&saml2Token=" + EeHttp.encode(ticket);
    }

    ExternalIdentity parseResponse(EeSaml2Config config, String xml, Instant now) {
        Document doc = EeXml.parse(xml);
        Element response = doc.getDocumentElement();
        if (!NS_PROTOCOL.equals(response.getNamespaceURI()) || !"Response".equals(response.getLocalName())) {
            throw new IllegalStateException("not a SAML Response");
        }
        String statusCode = null;
        Element status = child(response, NS_PROTOCOL, "Status");
        if (status != null) {
            Element code = child(status, NS_PROTOCOL, "StatusCode");
            statusCode = code == null ? null : code.getAttribute("Value");
        }
        if (!"urn:oasis:names:tc:SAML:2.0:status:Success".equals(statusCode)) {
            throw new IllegalStateException("SAML status: " + statusCode);
        }
        String destination = response.getAttribute("Destination");
        if (StringUtils.isNotBlank(destination) && !destination.equals(config.getSpAcs())) {
            throw new IllegalStateException("SAML Destination mismatch");
        }
        String inResponseTo = response.getAttribute("InResponseTo");
        Element issuer = child(response, NS_ASSERTION, "Issuer");
        if (issuer != null && !config.getIdpEntityId().equals(issuer.getTextContent().trim())) {
            throw new IllegalStateException("SAML Issuer mismatch");
        }
        if (child(response, NS_ASSERTION, "EncryptedAssertion") != null) {
            throw new IllegalStateException("encrypted assertion is not supported");
        }
        List<Element> assertions = children(response, NS_ASSERTION, "Assertion");
        if (assertions.size() != 1) {
            throw new IllegalStateException("SAML Response must contain exactly one Assertion");
        }
        Element assertion = assertions.get(0);
        List<X509Certificate> certs = new ArrayList<>();
        for (String c : config.getIdpCertificates() == null ? List.<String>of() : config.getIdpCertificates()) {
            certs.add(EeSamlCrypto.certificate(c));
        }
        response.setIdAttributeNS(null, "ID", true);
        assertion.setIdAttributeNS(null, "ID", true);
        Element responseSig = EeSamlCrypto.directSignature(response);
        Element assertionSig = EeSamlCrypto.directSignature(assertion);
        boolean responseSigned = responseSig != null && EeSamlCrypto.verify(responseSig, response.getAttribute("ID"), certs);
        boolean assertionSigned = assertionSig != null && EeSamlCrypto.verify(assertionSig, assertion.getAttribute("ID"), certs);
        if ((responseSig != null && !responseSigned) || (assertionSig != null && !assertionSigned)) {
            throw new IllegalStateException("SAML signature invalid");
        }
        if (!responseSigned && !assertionSigned) {
            throw new IllegalStateException("SAML Response is not signed");
        }
        Element assertionIssuer = child(assertion, NS_ASSERTION, "Issuer");
        if (assertionIssuer == null || !config.getIdpEntityId().equals(assertionIssuer.getTextContent().trim())) {
            throw new IllegalStateException("SAML Assertion Issuer mismatch");
        }
        String issueInstant = assertion.getAttribute("IssueInstant");
        if (StringUtils.isNotBlank(issueInstant)) {
            Instant issued = Instant.parse(issueInstant);
            if (issued.plusSeconds(config.getAssertionValidityTime() + SKEW_SECONDS).isBefore(now)
                    || issued.minusSeconds(SKEW_SECONDS).isAfter(now)) {
                throw new IllegalStateException("SAML Assertion expired");
            }
        }
        Element conditions = child(assertion, NS_ASSERTION, "Conditions");
        if (conditions != null) {
            checkTime(conditions.getAttribute("NotBefore"), conditions.getAttribute("NotOnOrAfter"), now);
            List<Element> restrictions = children(conditions, NS_ASSERTION, "AudienceRestriction");
            for (Element restriction : restrictions) {
                boolean matched = false;
                for (Element audience : children(restriction, NS_ASSERTION, "Audience")) {
                    if (config.getSpEntityId().equals(audience.getTextContent().trim())) {
                        matched = true;
                    }
                }
                if (!matched) {
                    throw new IllegalStateException("SAML Audience mismatch");
                }
            }
        }
        Element subject = child(assertion, NS_ASSERTION, "Subject");
        if (subject == null) {
            throw new IllegalStateException("SAML Subject missing");
        }
        boolean bearer = false;
        for (Element confirmation : children(subject, NS_ASSERTION, "SubjectConfirmation")) {
            if (!"urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(confirmation.getAttribute("Method"))) {
                continue;
            }
            Element data = child(confirmation, NS_ASSERTION, "SubjectConfirmationData");
            if (data != null) {
                checkTime(data.getAttribute("NotBefore"), data.getAttribute("NotOnOrAfter"), now);
                String recipient = data.getAttribute("Recipient");
                if (StringUtils.isNotBlank(recipient) && !recipient.equals(config.getSpAcs())) {
                    throw new IllegalStateException("SAML Recipient mismatch");
                }
                String dataInResponseTo = data.getAttribute("InResponseTo");
                if (StringUtils.isNotBlank(dataInResponseTo) && !dataInResponseTo.equals(inResponseTo)) {
                    throw new IllegalStateException("SAML InResponseTo mismatch");
                }
            }
            bearer = true;
        }
        if (!bearer) {
            throw new IllegalStateException("SAML bearer SubjectConfirmation missing");
        }
        // 签名与各项条件校验通过后才消费请求 ID，避免伪造的响应使合法登录失效
        checkInResponseTo(inResponseTo);
        Element nameId = child(subject, NS_ASSERTION, "NameID");
        String nameIdValue = nameId == null ? null : StringUtils.trimToNull(nameId.getTextContent());
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Element statement : children(assertion, NS_ASSERTION, "AttributeStatement")) {
            for (Element attribute : children(statement, NS_ASSERTION, "Attribute")) {
                List<Element> values = children(attribute, NS_ASSERTION, "AttributeValue");
                if (values.isEmpty()) {
                    continue;
                }
                String value = StringUtils.trimToNull(values.get(0).getTextContent());
                if (value == null) {
                    continue;
                }
                attributes.putIfAbsent(attribute.getAttribute("Name"), value);
                if (StringUtils.isNotBlank(attribute.getAttribute("FriendlyName"))) {
                    attributes.putIfAbsent(attribute.getAttribute("FriendlyName"), value);
                }
            }
        }
        Map<String, String> mapping = EeJson.mapping(config.getMapping());
        String account = StringUtils.defaultIfBlank(attr(attributes, mapping.get("account"), "uid", "account", "username"), nameIdValue);
        if (StringUtils.isBlank(account)) {
            throw new IllegalStateException("SAML account missing");
        }
        String name = attr(attributes, mapping.get("name"), "name", "displayName", "cn",
                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name");
        String email = attr(attributes, mapping.get("email"), "email", "mail",
                "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
        String phone = attr(attributes, mapping.get("phone"), "phone", "mobile");
        return new ExternalIdentity(EeConstants.ORIGIN_SAML, account, account, name, email, phone);
    }

    private void checkInResponseTo(String inResponseTo) {
        if (StringUtils.isBlank(inResponseTo)) {
            if (!allowIdpInitiated) {
                throw new IllegalStateException("unsolicited SAML Response is not allowed");
            }
            return;
        }
        if (ttlStore.get(EeSsoConstants.CACHE_SAML_REQUEST, inResponseTo) == null) {
            throw new IllegalStateException("SAML InResponseTo unknown or expired");
        }
        ttlStore.remove(EeSsoConstants.CACHE_SAML_REQUEST, inResponseTo);
    }

    private void checkTime(String notBefore, String notOnOrAfter, Instant now) {
        if (StringUtils.isNotBlank(notBefore) && Instant.parse(notBefore).minusSeconds(SKEW_SECONDS).isAfter(now)) {
            throw new IllegalStateException("SAML Assertion not yet valid");
        }
        if (StringUtils.isNotBlank(notOnOrAfter) && !Instant.parse(notOnOrAfter).plusSeconds(SKEW_SECONDS).isAfter(now)) {
            throw new IllegalStateException("SAML Assertion expired");
        }
    }

    private String attr(Map<String, String> attributes, String... keys) {
        for (String key : keys) {
            if (key != null && StringUtils.isNotBlank(attributes.get(key))) {
                return attributes.get(key);
            }
        }
        return null;
    }

    static Element child(Element parent, String ns, String name) {
        List<Element> list = children(parent, ns, name);
        return list.isEmpty() ? null : list.get(0);
    }

    static List<Element> children(Element parent, String ns, String name) {
        List<Element> list = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && ns.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) {
                list.add(element);
            }
        }
        return list;
    }

    private String deflate(String xml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFLATED, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            deflater.end();
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private X509Certificate safeCertificate(String pem) {
        try {
            return EeSamlCrypto.certificate(pem);
        } catch (Exception e) {
            return null;
        }
    }

    public record LoginRequest(String redirectUrl, String html) {
    }

    static class IdpMetadata {
        String entityId;
        String ssoUrl;
        String ssoBinding;
        String logoutUrl;
        List<String> certificates = new ArrayList<>();
    }

    /**
     * 持久化的 SAML2 配置：在 SDK VO 基础上保存 IdP 的 SSO 绑定与签名证书
     */
    @Getter
    @Setter
    public static class EeSaml2Config extends XpackSaml2VO {
        private String idpSsoBinding;
        private List<String> idpCertificates;
    }
}
