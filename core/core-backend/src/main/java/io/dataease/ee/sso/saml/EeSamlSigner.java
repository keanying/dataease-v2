package io.dataease.ee.sso.saml;

import io.dataease.ee.sso.support.EeXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * XML 封装签名（HTTP-POST 绑定的 AuthnRequest）
 */
public final class EeSamlSigner {

    private EeSamlSigner() {
    }

    public static String signEnveloped(String xml, String id, PrivateKey key, X509Certificate certificate) {
        try {
            Document doc = EeXml.parse(xml);
            Element root = doc.getDocumentElement();
            root.setIdAttributeNS(null, "ID", true);
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference("#" + id, factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
                    null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(EeSamlCrypto.SIG_ALG_RSA_SHA256, null), List.of(reference));
            KeyInfo keyInfo = null;
            if (certificate != null) {
                KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
                X509Data data = keyInfoFactory.newX509Data(List.of(certificate));
                keyInfo = keyInfoFactory.newKeyInfo(List.of(data));
            }
            // 签名须位于 Issuer 之后
            Node next = null;
            Element issuer = EeSaml2Manage.child(root, EeSaml2Manage.NS_ASSERTION, "Issuer");
            if (issuer != null) {
                next = issuer.getNextSibling();
            }
            DOMSignContext context = next == null ? new DOMSignContext(key, root) : new DOMSignContext(key, root, next);
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(signedInfo, keyInfo).sign(context);
            return EeXml.toString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("sign AuthnRequest failed: " + e.getMessage(), e);
        }
    }
}
