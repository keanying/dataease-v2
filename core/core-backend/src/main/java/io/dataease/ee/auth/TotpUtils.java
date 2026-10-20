package io.dataease.ee.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 基于时间的一次性密码（RFC 6238，30 秒步长，6 位，HMAC-SHA1），兼容常见身份验证器 App。
 */
public final class TotpUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtils() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new Base32().encodeToString(bytes).replace("=", "");
    }

    /**
     * 校验验证码
     *
     * @param window 允许的前后时间步偏移
     */
    public static boolean verify(String secret, String code, int window) {
        if (StringUtils.isBlank(secret) || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long step = System.currentTimeMillis() / 1000 / 30;
        int tolerance = Math.max(0, Math.min(window, 8));
        for (long i = -tolerance; i <= tolerance; i++) {
            if (code.equals(generate(secret, step + i))) {
                return true;
            }
        }
        return false;
    }

    static String generate(String secret, long step) {
        try {
            byte[] key = new Base32().decode(secret.toUpperCase());
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String otpAuthUri(String issuer, String account, String secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + "&digits=6&period=30";
    }

    /**
     * 生成二维码图片（data URI）
     */
    public static String qrDataUri(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
