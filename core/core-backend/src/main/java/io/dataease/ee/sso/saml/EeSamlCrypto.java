package io.dataease.ee.sso.saml;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.Reference;
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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * SAML 所需的密钥与签名工具（仅使用 JDK 实现）
 */
public final class EeSamlCrypto {

    public static final String SIG_ALG_RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

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
     * 校验 XML 签名：签名须恰好引用 {@code #id}，并能由任一 IdP 证书验证通过
     */
    public static boolean verify(Element signature, String id, List<X509Certificate> certificates) {
        if (signature == null || StringUtils.isBlank(id) || certificates == null || certificates.isEmpty()) {
            return false;
        }
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        for (X509Certificate certificate : certificates) {
            try {
                DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signature);
                context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
                XMLSignature xmlSignature = factory.unmarshalXMLSignature(context);
                List<?> references = xmlSignature.getSignedInfo().getReferences();
                if (references.size() != 1 || !("#" + id).equals(((Reference) references.get(0)).getURI())) {
                    return false;
                }
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
