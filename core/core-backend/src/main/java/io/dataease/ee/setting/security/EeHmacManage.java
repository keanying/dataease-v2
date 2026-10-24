package io.dataease.ee.setting.security;

import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HMAC 请求签名设置与校验算法。
 *
 * <p>签名串：{@code dataease-key\n<METHOD> <path>\nX-Date: <GMT>\n}，签名为 Base64(HMAC-SHA256(secret, 签名串))。</p>
 */
@Component
public class EeHmacManage {

    public static final String KEY_ID = "dataease-key";

    public static final int DEFAULT_CLOCK_SKEW = 300;

    /**
     * 设置缓存时间（毫秒）：多节点部署时其它节点最迟在该时间后生效
     */
    private static final long CACHE_MILLIS = 10_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EeSettingManage settingManage;

    private volatile Config cached;

    public EeHmacManage(EeSettingManage settingManage) {
        this.settingManage = settingManage;
    }

    public Config config() {
        Config local = cached;
        long now = System.currentTimeMillis();
        if (local == null || local.loadedAt + CACHE_MILLIS < now) {
            boolean enable = settingManage.getBool(EeSettingConstants.HMAC_ENABLE, false);
            String secret = settingManage.get(EeSettingConstants.HMAC_SECRET, "");
            int skew = settingManage.getInt(EeSettingConstants.HMAC_CLOCK_SKEW, DEFAULT_CLOCK_SKEW);
            local = new Config(enable && StringUtils.isNotBlank(secret), secret, skew <= 0 ? DEFAULT_CLOCK_SKEW : skew, now);
            cached = local;
        }
        return local;
    }

    public void evict() {
        cached = null;
    }

    public String newSecret() {
        return EeSettingSupport.randomAlphanumeric(32);
    }

    /**
     * /perSetting/hmac/info 的编码：P(16) + Base64(IV(16) || AES-256-CBC(JSON)) + S(16)，密钥为 P+S 的 UTF-8 字节
     */
    public String encodeInfo() {
        Config config = config();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enable", config.enable());
        info.put("secretKey", config.enable() ? config.secret() : "");
        String json = String.valueOf(JsonUtil.toJSONString(info));
        String prefix = EeSettingSupport.randomAlphanumeric(16);
        String suffix = EeSettingSupport.randomAlphanumeric(16);
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec((prefix + suffix).getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return prefix + Base64.getEncoder().encodeToString(combined) + suffix;
        } catch (Exception e) {
            throw new IllegalStateException("encode hmac info failed", e);
        }
    }

    public static String sign(String secret, String signingString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac sign failed", e);
        }
    }

    public static String signingString(String method, String path, String date) {
        return KEY_ID + "\n" + method + " " + path + "\nX-Date: " + date + "\n";
    }

    public static boolean constantEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param enable    是否启用（启用且密钥非空）
     * @param secret    密钥
     * @param clockSkew 允许的时间偏差（秒）
     * @param loadedAt  加载时间
     */
    public record Config(boolean enable, String secret, int clockSkew, long loadedAt) {
    }
}
