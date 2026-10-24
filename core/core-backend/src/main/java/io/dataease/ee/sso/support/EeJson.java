package io.dataease.ee.sso.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块内 JSON 工具：第三方接口响应解析、映射配置读取
 */
public final class EeJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EeJson() {
    }

    public static JsonNode tree(String json) {
        if (StringUtils.isBlank(json)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json: " + StringUtils.abbreviate(json, 200));
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /**
     * 按 a.b.c 路径读取文本值，数组取第一个元素
     */
    public static String text(JsonNode node, String path) {
        if (node == null || StringUtils.isBlank(path)) {
            return null;
        }
        JsonNode current = node;
        if (current.has(path)) {
            current = current.get(path);
        } else {
            for (String part : StringUtils.split(path, '.')) {
                if (current == null) {
                    return null;
                }
                if (current.isArray()) {
                    current = current.size() > 0 ? current.get(0) : null;
                    if (current == null) {
                        return null;
                    }
                }
                current = current.get(part);
            }
        }
        if (current == null || current.isNull() || current.isMissingNode()) {
            return null;
        }
        if (current.isArray()) {
            current = current.size() > 0 ? current.get(0) : null;
            if (current == null) {
                return null;
            }
        }
        String value = current.isValueNode() ? current.asText() : current.toString();
        return StringUtils.trimToNull(value);
    }

    /**
     * 解析属性映射 JSON（如 {"account":"uid","name":"cn","email":"mail"}），非法内容抛出异常
     */
    public static Map<String, String> mapping(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(json)) {
            return result;
        }
        JsonNode node = tree(json);
        if (!node.isObject()) {
            throw new IllegalArgumentException("mapping must be a json object");
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isValueNode() && StringUtils.isNotBlank(entry.getValue().asText())) {
                result.put(entry.getKey(), entry.getValue().asText().trim());
            }
        });
        return result;
    }
}
