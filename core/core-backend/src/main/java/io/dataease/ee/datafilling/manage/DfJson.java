package io.dataease.ee.datafilling.manage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtIndexField;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 表单 JSON 读写（忽略前端附带的界面字段）
 */
public final class DfJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private DfJson() {
    }

    public static List<ExtTableField> fields(String forms) {
        if (StringUtils.isBlank(forms)) {
            return new ArrayList<>();
        }
        List<ExtTableField> list = new ArrayList<>();
        for (JsonNode node : array(forms)) {
            list.add(field(node));
        }
        return list;
    }

    private static ExtTableField field(JsonNode node) {
        try {
            return MAPPER.treeToValue(node, ExtTableField.class);
        } catch (Exception e) {
            // 容错：去掉可能格式异常的辅助配置后再解析
            JsonNode copy = node.deepCopy();
            JsonNode settings = copy.get("settings");
            if (settings instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                obj.remove(List.of("defaultTime", "permissionsTree", "options", "extraColumns"));
            }
            try {
                return MAPPER.treeToValue(copy, ExtTableField.class);
            } catch (Exception ex) {
                DEException.throwException("Invalid form json: " + ex.getMessage());
            }
        }
        return null;
    }

    public static List<ExtTableField> activeFields(String forms) {
        List<ExtTableField> list = fields(forms);
        list.removeIf(ExtTableField::isRemoved);
        return list;
    }

    public static List<ExtIndexField> indexes(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            List<ExtIndexField> list = MAPPER.readValue(json, new TypeReference<List<ExtIndexField>>() {
            });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            DEException.throwException("Invalid index json: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    public static ArrayNode array(String json) {
        if (StringUtils.isBlank(json)) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node instanceof ArrayNode array ? array : MAPPER.createArrayNode();
        } catch (Exception e) {
            DEException.throwException("Invalid json: " + e.getMessage());
        }
        return MAPPER.createArrayNode();
    }

    public static <T> T read(String json, TypeReference<T> type) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
        return null;
    }
}
