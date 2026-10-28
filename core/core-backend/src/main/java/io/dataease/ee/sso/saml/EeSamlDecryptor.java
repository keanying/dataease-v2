package io.dataease.ee.sso.saml;

import io.dataease.ee.sso.support.EeXml;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;

/**
 * SAML 加密断言（{@code saml:EncryptedAssertion}）解密，仅使用 JDK 实现（XML Encryption 1.1 子集）：
 * <ul>
 *     <li>密钥传输：RSA-OAEP（{@code xmlenc#rsa-oaep-mgf1p}、{@code xmlenc11#rsa-oaep}，摘要 SHA-1/224/256/384/512，MGF1 同）、RSA1_5；</li>
 *     <li>内容加密：AES-128/192/256-CBC、AES-128/192/256-GCM、3DES-CBC。</li>
 * </ul>
 * EncryptedKey 可位于 EncryptedData 的 ds:KeyInfo 中，或作为 EncryptedAssertion 的子元素（RetrievalMethod 引用）。
 */
public final class EeSamlDecryptor {

    static final String NS_XENC = "http://www.w3.org/2001/04/xmlenc#";

    static final String NS_XENC11 = "http://www.w3.org/2009/xmlenc11#";

    private static final String RSA_1_5 = NS_XENC + "rsa-1_5";

    private static final String RSA_OAEP_MGF1P = NS_XENC + "rsa-oaep-mgf1p";

    private static final String RSA_OAEP = NS_XENC11 + "rsa-oaep";

    private static final Map<String, String> DIGESTS = Map.of(
            "http://www.w3.org/2000/09/xmldsig#sha1", "SHA-1",
            "http://www.w3.org/2001/04/xmldsig-more#sha224", "SHA-224",
            "http://www.w3.org/2001/04/xmlenc#sha256", "SHA-256",
            "http://www.w3.org/2001/04/xmldsig-more#sha384", "SHA-384",
            "http://www.w3.org/2001/04/xmlenc#sha512", "SHA-512");

    private static final Map<String, String> MGFS = Map.of(
            NS_XENC11 + "mgf1sha1", "SHA-1",
            NS_XENC11 + "mgf1sha224", "SHA-224",
            NS_XENC11 + "mgf1sha256", "SHA-256",
            NS_XENC11 + "mgf1sha384", "SHA-384",
            NS_XENC11 + "mgf1sha512", "SHA-512");

    /**
     * 内容加密算法 -> {JCE 算法, 密钥字节数}
     */
    private static final Map<String, Object[]> BLOCK = Map.of(
            NS_XENC + "aes128-cbc", new Object[]{"AES/CBC/NoPadding", 16},
            NS_XENC + "aes192-cbc", new Object[]{"AES/CBC/NoPadding", 24},
            NS_XENC + "aes256-cbc", new Object[]{"AES/CBC/NoPadding", 32},
            NS_XENC11 + "aes128-gcm", new Object[]{"AES/GCM/NoPadding", 16},
            NS_XENC11 + "aes192-gcm", new Object[]{"AES/GCM/NoPadding", 24},
            NS_XENC11 + "aes256-gcm", new Object[]{"AES/GCM/NoPadding", 32},
            NS_XENC + "tripledes-cbc", new Object[]{"DESede/CBC/NoPadding", 24});

    private EeSamlDecryptor() {
    }

    /**
     * 配置类错误（算法不受支持），可直接提示
     */
    private static class UnsupportedAlgorithm extends IllegalStateException {
        UnsupportedAlgorithm(String message) {
            super(message);
        }
    }

    /**
     * 解密 EncryptedAssertion，返回已导入到同一文档中的 saml:Assertion（调用方负责替换节点）
     */
    public static Element decryptAssertion(Element encryptedAssertion, PrivateKey key) {
        if (key == null) {
            throw new IllegalStateException("SAML assertion is encrypted but no SP private key is configured");
        }
        Element encryptedData = EeSaml2Manage.child(encryptedAssertion, NS_XENC, "EncryptedData");
        if (encryptedData == null) {
            throw new IllegalStateException("EncryptedData missing in EncryptedAssertion");
        }
        String type = encryptedData.getAttribute("Type");
        if (StringUtils.isNotBlank(type) && !(NS_XENC + "Element").equals(type)) {
            throw new IllegalStateException("unsupported EncryptedData Type: " + type);
        }
        Element method = EeSaml2Manage.child(encryptedData, NS_XENC, "EncryptionMethod");
        String algorithm = method == null ? null : method.getAttribute("Algorithm");
        Object[] block = algorithm == null ? null : BLOCK.get(algorithm);
        if (block == null) {
            throw new IllegalStateException("unsupported data encryption algorithm: " + algorithm);
        }
        int keyLength = (Integer) block[1];
        List<Element> encryptedKeys = encryptedKeys(encryptedAssertion, encryptedData);
        if (encryptedKeys.isEmpty()) {
            throw new IllegalStateException("EncryptedKey missing in EncryptedAssertion");
        }
        byte[] cipherText = cipherValue(encryptedData);
        for (Element encryptedKey : encryptedKeys) {
            try {
                byte[] secret = unwrapKey(encryptedKey, key, keyLength);
                byte[] plain = decryptData((String) block[0], secret, cipherText);
                return parseFragment(encryptedAssertion, new String(plain, StandardCharsets.UTF_8));
            } catch (UnsupportedAlgorithm e) {
                throw new IllegalStateException(e.getMessage());
            } catch (Exception e) {
                // 不区分填充错误、解析错误等具体原因，避免成为 CBC 填充 / 解析预言（XML Encryption 已知攻击）
                LogUtil.debug("decrypt SAML assertion failed: " + e.getMessage());
            }
        }
        throw new IllegalStateException("decrypt SAML assertion failed (check that the IdP encrypts with the SP certificate)");
    }

    private static List<Element> encryptedKeys(Element encryptedAssertion, Element encryptedData) {
        List<Element> result = new ArrayList<>();
        Element keyInfo = EeSaml2Manage.child(encryptedData, EeSaml2Manage.NS_DSIG, "KeyInfo");
        if (keyInfo != null) {
            result.addAll(EeSaml2Manage.children(keyInfo, NS_XENC, "EncryptedKey"));
        }
        result.addAll(EeSaml2Manage.children(encryptedAssertion, NS_XENC, "EncryptedKey"));
        return result;
    }

    private static byte[] unwrapKey(Element encryptedKey, PrivateKey key, int keyLength) throws Exception {
        Element method = EeSaml2Manage.child(encryptedKey, NS_XENC, "EncryptionMethod");
        String algorithm = method == null ? null : method.getAttribute("Algorithm");
        byte[] wrapped = cipherValue(encryptedKey);
        if (RSA_1_5.equals(algorithm)) {
            // 防止 Bleichenbacher 填充预言：解密失败时使用随机密钥继续（内容解密随之失败）
            byte[] secret;
            try {
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, key);
                secret = cipher.doFinal(wrapped);
            } catch (Exception e) {
                secret = null;
            }
            if (secret == null || secret.length != keyLength) {
                secret = new byte[keyLength];
                new SecureRandom().nextBytes(secret);
            }
            return secret;
        }
        if (!RSA_OAEP_MGF1P.equals(algorithm) && !RSA_OAEP.equals(algorithm)) {
            throw new UnsupportedAlgorithm("unsupported key transport algorithm: " + algorithm);
        }
        String digest = "SHA-1";
        String mgf = "SHA-1";
        byte[] label = new byte[0];
        Element digestMethod = EeSaml2Manage.child(method, EeSaml2Manage.NS_DSIG, "DigestMethod");
        if (digestMethod != null) {
            digest = DIGESTS.get(digestMethod.getAttribute("Algorithm"));
            if (digest == null) {
                throw new UnsupportedAlgorithm("unsupported OAEP digest: " + digestMethod.getAttribute("Algorithm"));
            }
        }
        Element mgfMethod = EeSaml2Manage.child(method, NS_XENC11, "MGF");
        if (mgfMethod != null) {
            if (!RSA_OAEP.equals(algorithm)) {
                throw new UnsupportedAlgorithm("MGF is only allowed with xmlenc11#rsa-oaep");
            }
            mgf = MGFS.get(mgfMethod.getAttribute("Algorithm"));
            if (mgf == null) {
                throw new UnsupportedAlgorithm("unsupported OAEP MGF: " + mgfMethod.getAttribute("Algorithm"));
            }
        }
        Element params = EeSaml2Manage.child(method, NS_XENC, "OAEPparams");
        if (params != null && StringUtils.isNotBlank(params.getTextContent())) {
            label = Base64.getMimeDecoder().decode(params.getTextContent().trim());
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec(digest, "MGF1", new MGF1ParameterSpec(mgf), new PSource.PSpecified(label)));
        byte[] secret = cipher.doFinal(wrapped);
        if (secret.length != keyLength) {
            throw new IllegalStateException("unexpected content key length");
        }
        return secret;
    }

    private static byte[] decryptData(String transformation, byte[] secret, byte[] data) throws Exception {
        String keyAlgorithm = transformation.startsWith("DESede") ? "DESede" : "AES";
        SecretKeySpec keySpec = new SecretKeySpec(secret, keyAlgorithm);
        Cipher cipher = Cipher.getInstance(transformation);
        if (transformation.contains("GCM")) {
            int ivLength = 12;
            if (data.length <= ivLength + 16) {
                throw new IllegalStateException("cipher text too short");
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, data, 0, ivLength));
            return cipher.doFinal(data, ivLength, data.length - ivLength);
        }
        int ivLength = cipher.getBlockSize();
        if (data.length < ivLength * 2 || (data.length - ivLength) % ivLength != 0) {
            throw new IllegalStateException("invalid cipher text length");
        }
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(data, 0, ivLength));
        byte[] plain = cipher.doFinal(data, ivLength, data.length - ivLength);
        // XML Encryption 填充：最后一个字节为填充长度（填充内容任意）
        int pad = plain[plain.length - 1] & 0xff;
        if (pad < 1 || pad > ivLength) {
            throw new IllegalStateException("invalid padding");
        }
        return Arrays.copyOf(plain, plain.length - pad);
    }

    private static byte[] cipherValue(Element parent) {
        Element cipherData = EeSaml2Manage.child(parent, NS_XENC, "CipherData");
        Element value = cipherData == null ? null : EeSaml2Manage.child(cipherData, NS_XENC, "CipherValue");
        if (value == null || StringUtils.isBlank(value.getTextContent())) {
            throw new IllegalStateException("CipherValue missing (CipherReference is not supported)");
        }
        return Base64.getMimeDecoder().decode(value.getTextContent().trim());
    }

    /**
     * 明文为序列化的元素，可能依赖 EncryptedAssertion 上下文中声明的命名空间：包装在声明了这些命名空间的元素中解析
     */
    private static Element parseFragment(Element context, String plain) {
        String text = plain.trim();
        if (text.startsWith("<?xml")) {
            int end = text.indexOf("?>");
            text = end < 0 ? text : text.substring(end + 2);
        }
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (Node node = context; node instanceof Element element; node = node.getParentNode()) {
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Attr attr = (Attr) attributes.item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(attr.getNamespaceURI())) {
                    String prefix = "xmlns".equals(attr.getName()) ? "" : attr.getLocalName();
                    namespaces.putIfAbsent(prefix, attr.getValue());
                }
            }
        }
        StringBuilder wrapper = new StringBuilder("<dewrapper");
        namespaces.forEach((prefix, uri) -> wrapper.append(prefix.isEmpty() ? " xmlns" : " xmlns:" + prefix)
                .append("=\"").append(EeXml.escape(uri)).append('"'));
        wrapper.append('>').append(text).append("</dewrapper>");
        Document fragment = EeXml.parse(wrapper.toString());
        Element result = null;
        for (Node node = fragment.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (result != null) {
                    throw new IllegalStateException("decrypted content must be a single element");
                }
                result = element;
            }
        }
        if (result == null || !EeSaml2Manage.NS_ASSERTION.equals(result.getNamespaceURI()) || !"Assertion".equals(result.getLocalName())) {
            throw new IllegalStateException("decrypted content is not a SAML Assertion");
        }
        return (Element) context.getOwnerDocument().importNode(result, true);
    }
}
