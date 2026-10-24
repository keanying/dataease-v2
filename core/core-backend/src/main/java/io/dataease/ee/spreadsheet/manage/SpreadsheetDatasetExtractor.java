package io.dataease.ee.spreadsheet.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.ee.spreadsheet.SpreadsheetConstants;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 从 Univer 工作簿 JSON 的插件资源（resources[].data）中解析引用的数据集ID。
 */
public final class SpreadsheetDatasetExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpreadsheetDatasetExtractor() {
    }

    public static Set<Long> extract(String sheetData) {
        Set<Long> result = new LinkedHashSet<>();
        if (StringUtils.isBlank(sheetData)) {
            return result;
        }
        try {
            JsonNode root = MAPPER.readTree(sheetData);
            JsonNode resources = root == null ? null : root.get("resources");
            if (resources == null || !resources.isArray()) {
                return result;
            }
            for (JsonNode resource : resources) {
                String name = resource.path("name").asText("");
                if (!SpreadsheetConstants.PLUGIN_RESOURCE_NAMES.contains(name)) {
                    continue;
                }
                JsonNode data = resource.get("data");
                if (data == null || data.isNull()) {
                    continue;
                }
                JsonNode instances = data.isTextual() ? MAPPER.readTree(data.asText()) : data;
                collect(instances, result, 0);
            }
        } catch (Exception e) {
            LogUtil.warn("parse spreadsheet datasets failed: " + e.getMessage());
        }
        return result;
    }

    private static void collect(JsonNode node, Set<Long> result, int depth) {
        if (node == null || depth > 64) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collect(item, result, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (SpreadsheetConstants.DATASET_KEYS.contains(entry.getKey()) && value != null && value.isValueNode()) {
                Long id = toLong(value.asText());
                if (id != null && id > 0) {
                    result.add(id);
                }
            } else {
                collect(value, result, depth + 1);
            }
        }
    }

    private static Long toLong(String text) {
        if (StringUtils.isBlank(text) || !StringUtils.isNumeric(text.trim())) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
