package io.dataease.ee.sso.support;

import io.dataease.utils.RsaUtils;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 由系统 RSA 私钥派生的各用途 HMAC 密钥（服务端保密，集群各节点一致）
 */
public final class EeSsoKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private EeSsoKeys() {
    }

    /**
     * 派生指定用途的密钥
     */
    public static byte[] derive(String purpose) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, StringUtils.defaultString(RsaUtils.privateKey()))
                .hmac("dataease-ee-sso:" + purpose);
    }

    public static String hmacHex(String purpose, String data) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, derive(purpose)).hmacHex(StringUtils.defaultString(data));
    }

    public static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * 常量时间比较
     */
    public static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
