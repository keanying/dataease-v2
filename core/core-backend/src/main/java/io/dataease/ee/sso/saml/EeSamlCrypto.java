package io.dataease.ee.sso.saml;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * SAML 所需的密钥与签名工具（仅使用 JDK 实现）
 */
public final class EeSamlCrypto {

    public static final String SIG_ALG_RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    public static final String SIG_ALG_RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";

    public static final String DIGEST_SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1";

    private static final Set<String> SIGNATURE_ALGS = Set.of(SIG_ALG_RSA_SHA1, SIG_ALG_RSA_SHA256,
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384", "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256", "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512");

    private static final Set<String> DIGEST_ALGS = Set.of(DIGEST_SHA1, "http://www.w3.org/2001/04/xmlenc#sha256",
            "http://www.w3.org/2001/04/xmldsig-more#sha384", "http://www.w3.org/2001/04/xmlenc#sha512");

    private static final Set<String> C14N_ALGS = Set.of("http://www.w3.org/2001/10/xml-exc-c14n#",
            "http://www.w3.org/2001/10/xml-exc-c14n#WithComments", "http://www.w3.org/TR/2001/REC-xml-c14n-20010315",
            "http://www.w3.org/TR/2001/REC-xml-c14n-20010315#WithComments", "http://www.w3.org/2006/12/xml-c14n11",
            "http://www.w3.org/2006/12/xml-c14n11#WithComments");

    private static final Set<String> TRANSFORMS;

    static {
        Set<String> transforms = new java.util.HashSet<>(C14N_ALGS);
        transforms.add("http://www.w3.org/2000/09/xmldsig#enveloped-signature");
        TRANSFORMS = Set.copyOf(transforms);
    }

    private static final String SECURE_VALIDATION = "org.jcp.xml.dsig.secureValidation";

    private EeSamlCrypto() {
    }

    public static X509Certificate certificate(String pemOrBase64) {
        if (StringUtils.isBlank(pemOrBase64)) {
            return null;
        }
        String body = pemOrBase64.replaceAll("-----BEGIN CERTIFICATE-----", "").replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid certificate: " + e.getMessage());
        }
    }

    /**
     * 解析 RSA 私钥（PKCS#8 "PRIVATE KEY" 或 PKCS#1 "RSA PRIVATE KEY"）
     */
    public static PrivateKey privateKey(String pem) {
        if (StringUtils.isBlank(pem)) {
            return null;
        }
        if (pem.contains("ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException("encrypted private key is not supported");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            if (pkcs1) {
                der = wrapPkcs1(der);
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid private key: " + e.getMessage());
        }
    }

    /**
     * 校验私钥与证书是否匹配
     */
    public static void checkPair(PrivateKey key, X509Certificate certificate) {
        try {
            byte[] data = "dataease-saml-key-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(data);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(data);
            if (!verifier.verify(sig)) {
                throw new IllegalArgumentException("private key does not match certificate");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("private key does not match certificate: " + e.getMessage());
        }
    }

    public static byte[] signRsaSha256(PrivateKey key, String data) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(data.getBytes(StandardCharsets.UTF_8));
            return signer.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 校验 XML 签名：签名须恰好引用 {@code #id}，并能由任一 IdP 证书验证通过（不接受 SHA-1）
     */
    public static boolean verify(Element signature, String id, List<X509Certificate> certificates) {
        return verify(signature, id, certificates, false);
    }

    /**
     * 校验 XML 签名：签名须恰好引用 {@code #id}，变换只允许 enveloped-signature 与 C14N，并能由任一 IdP 证书验证通过。
     *
     * <p>JDK 的安全校验模式拒绝 SHA-1 签名与摘要；{@code allowSha1} 为 true 且签名使用 SHA-1 时关闭安全校验模式，
     * 由本方法自行限制算法、引用与变换（仍拒绝 XSLT / XPath 等变换和 1024 位以下的 RSA 密钥）。</p>
     */
    public static boolean verify(Element signature, String id, List<X509Certificate> certificates, boolean allowSha1) {
        if (signature == null || StringUtils.isBlank(id) || certificates == null || certificates.isEmpty()) {
            return false;
        }
        if (countId(signature.getOwnerDocument().getDocumentElement(), id) != 1) {
            // 重复 ID（签名包装攻击）
            return false;
        }
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        for (X509Certificate certificate : certificates) {
            try {
                // 先以非安全模式解析以检查算法（安全模式会直接拒绝 SHA-1），检查通过后再按需开启安全模式校验
                DOMValidateContext probe = new DOMValidateContext(certificate.getPublicKey(), signature);
                probe.setProperty(SECURE_VALIDATION, Boolean.FALSE);
                XMLSignature parsed = factory.unmarshalXMLSignature(probe);
                List<?> references = parsed.getSignedInfo().getReferences();
                if (references.size() != 1 || !("#" + id).equals(((Reference) references.get(0)).getURI())) {
                    return false;
                }
                Reference reference = (Reference) references.get(0);
                String signatureAlg = parsed.getSignedInfo().getSignatureMethod().getAlgorithm();
                String digestAlg = reference.getDigestMethod().getAlgorithm();
                if (!SIGNATURE_ALGS.contains(signatureAlg) || !DIGEST_ALGS.contains(digestAlg)
                        || !C14N_ALGS.contains(parsed.getSignedInfo().getCanonicalizationMethod().getAlgorithm())) {
                    return false;
                }
                List<?> transforms = reference.getTransforms();
                if (transforms.size() > 3) {
                    return false;
                }
                for (Object transform : transforms) {
                    if (!TRANSFORMS.contains(((Transform) transform).getAlgorithm())) {
                        return false;
                    }
                }
                boolean sha1 = SIG_ALG_RSA_SHA1.equals(signatureAlg) || DIGEST_SHA1.equals(digestAlg);
                if (sha1 && !allowSha1) {
                    return false;
                }
                if (certificate.getPublicKey() instanceof RSAKey rsa && rsa.getModulus().bitLength() < 1024) {
                    return false;
                }
                DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signature);
                context.setProperty(SECURE_VALIDATION, !sha1);
                XMLSignature xmlSignature = factory.unmarshalXMLSignature(context);
                if (xmlSignature.validate(context)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 尝试下一个证书
            }
        }
        return false;
    }

    /**
     * 签名是否使用 SHA-1（签名算法或摘要算法）
     */
    public static boolean usesSha1(Element signature) {
        if (signature == null) {
            return false;
        }
        for (String tag : new String[]{"SignatureMethod", "DigestMethod"}) {
            var nodes = signature.getElementsByTagNameNS(XMLSignature.XMLNS, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                String alg = ((Element) nodes.item(i)).getAttribute("Algorithm");
                if (SIG_ALG_RSA_SHA1.equals(alg) || DIGEST_SHA1.equals(alg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countId(Element element, String id) {
        int count = id.equals(element.getAttribute("ID")) ? 1 : 0;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e) {
                count += countId(e, id);
            }
        }
        return count;
    }

    /**
     * 元素的直接子元素中的 ds:Signature
     */
    public static Element directSignature(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && XMLSignature.XMLNS.equals(element.getNamespaceURI())
                    && "Signature".equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    /**
     * PKCS#1 RSAPrivateKey 包装为 PKCS#8 PrivateKeyInfo
     */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        ByteArrayOutputStream octet = new ByteArrayOutputStream();
        octet.write(0x04);
        writeLength(octet, pkcs1.length);
        octet.writeBytes(pkcs1);
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(version);
        content.writeBytes(algorithm);
        content.writeBytes(octet.toByteArray());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(0x30);
        writeLength(result, content.size());
        result.writeBytes(content.toByteArray());
        return result.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        byte[] bytes = BigInteger.valueOf(length).toByteArray();
        int offset = bytes[0] == 0 ? 1 : 0;
        out.write(0x80 | (bytes.length - offset));
        out.write(bytes, offset, bytes.length - offset);
    }
}
