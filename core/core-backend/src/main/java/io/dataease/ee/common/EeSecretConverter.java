package io.dataease.ee.common;

import io.dataease.utils.EncryptUtils;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.apache.commons.lang3.StringUtils;

/**
 * 密钥类字段的落库加密：写入时加 {@code ENC:} 前缀并用安装级 AES 密钥（{@link EncryptUtils}，与数据源配置相同）加密，
 * 读取时解密；没有前缀的历史明文按原值读取，下次保存时加密。
 *
 * <p>使用方式：在实体字段上标注 {@code @Convert(converter = EeSecretConverter.class)}，并为密文预留足够的列长度
 * （约为明文长度的 4/3 再加 30 个字符）。</p>
 */
@Converter
public class EeSecretConverter implements AttributeConverter<String, String> {

    public static final String PREFIX = "ENC:";

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return decrypt(dbData);
    }

    public static String encrypt(String plain) {
        if (StringUtils.isEmpty(plain) || plain.startsWith(PREFIX)) {
            return plain;
        }
        return PREFIX + EncryptUtils.aesEncrypt(plain);
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        Object plain = EncryptUtils.aesDecrypt(stored.substring(PREFIX.length()));
        return plain == null ? null : plain.toString();
    }
}
