package io.dataease.ee.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.dao.auto.entity.VisualizationReportFilter;
import io.dataease.dao.auto.repo.VisualizationReportFilterRepository;
import io.dataease.ee.report.dao.XpackReportTask;
import io.dataease.extensions.view.dto.SqlVariableDetails;
import io.dataease.extensions.view.dto.ChartExtFilterDTO;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 定时报告的过滤条件：按报告设置的查询组件默认值（{@code visualization_report_filter}）替换仪表板中的查询组件条件，
 * 计算出某个图表首次加载时的过滤条件（与预览页面一致），供 Excel 附件的图表数据查询使用。
 *
 * <p>逻辑移植自前端 {@code hooks/web/useFilter.ts} 的 {@code searchQuery(..., firstLoad = true)}、
 * {@code custom-component/v-query/time-format.ts} 与 {@code time-format-dayjs.ts}：
 * 下拉/列表/树（in）、文本搜索（eq/like 等及其与/或组合）、数值区间（between）、固定与动态时间、时间范围（between，
 * 含起止字段分别映射的情况）以及选项过滤（optionFilter）。动态时间以服务器时区计算，周从周一开始。</p>
 */
@Component
public class ReportFilterResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final DateTimeFormatter[] PATTERNS = {
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")};

    private final VisualizationReportFilterRepository reportFilterRepository;

    public ReportFilterResolver(VisualizationReportFilterRepository reportFilterRepository) {
        this.reportFilterRepository = reportFilterRepository;
    }

    /**
     * 报告设置的查询条件：filterId → 过滤项 JSON
     */
    public Map<String, JsonNode> reportFilters(XpackReportTask task) {
        Map<String, JsonNode> map = new HashMap<>();
        if (task == null || task.getRid() == null) {
            return map;
        }
        for (VisualizationReportFilter f : reportFilterRepository.findByResourceIdAndTaskId(task.getRid(), task.getId())) {
            if (f.getFilterId() == null || StringUtils.isBlank(f.getFilterInfo())) {
                continue;
            }
            try {
                map.put(String.valueOf(f.getFilterId()), MAPPER.readTree(f.getFilterInfo()));
            } catch (Exception e) {
                LogUtil.warn("invalid report filter " + f.getId() + ": " + e.getMessage());
            }
        }
        return map;
    }

    /**
     * 查询组件（含分组、Tab 中的）在图表 viewId 上产生的过滤条件
     */
    public List<ChartExtFilterDTO> filters(DataVisualizationInfo dv, Map<String, JsonNode> reportFilters, Long viewId) {
        List<ChartExtFilterDTO> result = new ArrayList<>();
        if (dv == null || StringUtils.isBlank(dv.getComponentData()) || viewId == null) {
            return result;
        }
        JsonNode components;
        boolean popupAvailable = true;
        try {
            components = MAPPER.readTree(dv.getComponentData());
            if (StringUtils.isNotBlank(dv.getCanvasStyleData())) {
                JsonNode style = MAPPER.readTree(dv.getCanvasStyleData());
                popupAvailable = style.path("popupAvailable").asBoolean(true);
            }
        } catch (Exception e) {
            LogUtil.warn("parse component data failed: " + e.getMessage());
            return result;
        }
        List<JsonNode> queries = new ArrayList<>();
        collectQueries(components, queries, popupAvailable);
        String chartId = String.valueOf(viewId);
        for (JsonNode query : queries) {
            JsonNode items = query.path("propValue");
            if (!items.isArray()) {
                continue;
            }
            Long componentId = toLong(query.path("id").asText(null));
            for (JsonNode raw : items) {
                JsonNode item = raw;
                String itemId = raw.path("id").asText("");
                if (reportFilters != null && reportFilters.containsKey(itemId)) {
                    item = reportFilters.get(itemId);
                }
                if (item instanceof ObjectNode obj) {
                    try {
                        searchItem(obj.deepCopy(), componentId, chartId, result);
                    } catch (Exception e) {
                        LogUtil.warn("resolve report filter " + itemId + " failed: " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    private void collectQueries(JsonNode node, List<JsonNode> queries, boolean popupAvailable) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode ele : node) {
            if (!popupAvailable && StringUtils.equals(ele.path("category").asText(), "hidden")) {
                continue;
            }
            String component = ele.path("component").asText("");
            String innerType = ele.path("innerType").asText("");
            if (StringUtils.equals(component, "VQuery") || StringUtils.equals(innerType, "VQuery")) {
                queries.add(ele);
            } else if (StringUtils.equals(component, "Group")) {
                collectQueries(ele.path("propValue"), queries, popupAvailable);
            } else if (StringUtils.equals(component, "DeTabs") || StringUtils.equals(innerType, "DeTabs")) {
                for (JsonNode tab : ele.path("propValue")) {
                    collectQueries(tab.path("componentData"), queries, popupAvailable);
                }
            }
        }
    }

    // ------------------------------------------------------------------ searchQuery（firstLoad = true）

    private void searchItem(ObjectNode item, Long componentId, String chartId, List<ChartExtFilterDTO> out) {
        String displayType = item.path("displayType").asText("");
        int type = toInt(displayType);
        boolean isTree = type == 9;
        List<Integer> relationshipChartIndex = new ArrayList<>();
        String[] ids = new String[]{"1", "1", "1", "1", "1"};
        boolean shouldSearch = false;
        JsonNode treeCheckedList = item.path("treeCheckedList");
        JsonNode treeFieldList = item.path("treeFieldList");
        if (StringUtils.equals(displayType, "9") && treeCheckedList.isArray() && !treeCheckedList.isEmpty()) {
            for (int idx = 0; idx < treeCheckedList.size(); idx++) {
                JsonNode itx = treeCheckedList.get(idx);
                String mapped = itx.path("checkedFieldsMap").path(chartId).asText("");
                if (contains(itx.path("checkedFields"), chartId) && StringUtils.isNotEmpty(mapped) && idx < treeFieldList.size()) {
                    relationshipChartIndex.add(idx);
                    if (idx < ids.length) {
                        ids[idx] = mapped;
                    }
                }
            }
        } else {
            shouldSearch = contains(item.path("checkedFields"), chartId)
                    && StringUtils.isNotEmpty(item.path("checkedFieldsMap").path(chartId).asText(""));
        }
        if (!shouldSearch && relationshipChartIndex.isEmpty()) {
            return;
        }
        boolean multiple = item.path("multiple").asBoolean(false);
        JsonNode optionFilter = item.get("optionFilter");
        if (optionFilter != null && optionFilter.isArray() && !optionFilter.isEmpty()) {
            String fieldIdOption = item.path("checkedFieldsMap").path(chartId).asText("");
            List<String> values = new ArrayList<>();
            optionFilter.forEach(v -> values.add(isTree ? v.asText().replace("-de-", ",") : v.asText()));
            List<String> filterValues = values;
            if (isTree) {
                FieldResult r = fieldId(treeFieldList, new ArrayList<>(values), relationshipChartIndex, ids);
                fieldIdOption = r.fieldId;
            }
            out.add(filter(componentId, fieldIdOption, "in", filterValues, List.of(), isTree));
        }

        boolean defaultValueCheck = item.path("defaultValueCheck").asBoolean(false);
        String timeType = item.path("timeType").asText("fixed");
        String timeGranularity = item.path("timeGranularity").asText("date");
        String timeGranularityMultiple = item.path("timeGranularityMultiple").asText("");
        int conditionType = item.path("conditionType").asInt(0);
        JsonNode selectValue;
        if (StringUtils.equals(timeType, "dynamic") && (type == 1 || type == 7) && defaultValueCheck) {
            if (type == 1) {
                selectValue = longArray(dynamicRange(item));
            } else {
                LocalDateTime start = customTime(item.path("timeNum").asInt(0), item.path("relativeToCurrentType").asText("date"),
                        timeGranularity, item.path("around").asText("f"), parseTime(item.get("arbitraryTime")), timeGranularityMultiple, "start-panel");
                LocalDateTime end = customTime(item.path("timeNumRange").asInt(0), item.path("relativeToCurrentTypeRange").asText("date"),
                        timeGranularity, item.path("aroundRange").asText("f"), parseTime(item.get("arbitraryTimeRange")), timeGranularityMultiple, "end-panel");
                String range = item.path("relativeToCurrentRange").asText("");
                if (StringUtils.isNotEmpty(range) && !StringUtils.equals(range, "custom")) {
                    LocalDateTime[] r = customRange(range);
                    start = r[0];
                    end = r[1];
                }
                selectValue = longArray(new Long[]{millis(start), millis(end)});
            }
        } else if (StringUtils.equals(displayType, "8")) {
            String f = item.path("defaultConditionValueF").asText("");
            String s = item.path("defaultConditionValueS").asText("");
            if (conditionType == 0) {
                selectValue = f.isEmpty() ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.textNode(f);
            } else {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                if (!f.isEmpty()) {
                    arr.add(f);
                }
                if (!s.isEmpty()) {
                    arr.add(s);
                }
                selectValue = arr;
            }
        } else if (StringUtils.equals(displayType, "22")) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            if (defaultValueCheck) {
                JsonNode start = item.get("defaultNumValueStart");
                JsonNode end = item.get("defaultNumValueEnd");
                if (start != null && !start.isNull() && !start.asText().isEmpty()) {
                    arr.add(start);
                }
                if (end != null && !end.isNull() && !end.asText().isEmpty()) {
                    arr.add(end);
                }
            }
            selectValue = arr;
        } else {
            selectValue = defaultSelectValue(item, type, multiple, defaultValueCheck);
        }
        if (!hasValue(selectValue) && !StringUtils.equals(displayType, "8")) {
            return;
        }
        List<String> result = formatValue(type, selectValue, timeGranularity, timeGranularityMultiple);
        String operator = operator(displayType, type, conditionType, item);
        if (result == null || result.isEmpty()) {
            return;
        }
        String fieldId = item.path("checkedFieldsMap").path(chartId).asText("");
        if (isTree) {
            FieldResult r = fieldId(treeFieldList, result, relationshipChartIndex, ids);
            fieldId = r.fieldId;
            result = r.values;
        }
        List<JsonNode> parametersFilter = new ArrayList<>();
        for (JsonNode p : item.path("parameters")) {
            if (StringUtils.equals(p.path("id").asText(), fieldId)) {
                parametersFilter.add(p);
                break;
            }
        }
        for (String[] keys : new String[][]{{"checkedFieldsMapArr", "checkedFieldsMapEnd"}, {"checkedFieldsMapArrNum", "checkedFieldsMapEndNum"}}) {
            JsonNode arr = item.path(keys[0]).path(chartId);
            if (!arr.isArray() || arr.isEmpty() || result.size() < 2) {
                continue;
            }
            String startFieldId = fieldId;
            String endFieldId = null;
            for (JsonNode e : arr) {
                if (!StringUtils.equals(e.asText(), startFieldId)) {
                    endFieldId = e.asText();
                    break;
                }
            }
            if (endFieldId == null) {
                continue;
            }
            boolean isEnd = StringUtils.equals(endFieldId, item.path(keys[1]).path(chartId).asText(null));
            String endValue = isEnd ? result.get(1) : result.get(0);
            String startValue = isEnd ? result.get(0) : result.get(1);
            List<String> resultEnd = List.of(endValue, endValue);
            result = List.of(startValue, startValue);
            parametersFilter = distinctById(item.path("parametersArr").path(chartId), startFieldId);
            List<JsonNode> parametersEnd = distinctById(item.path("parametersArr").path(chartId), endFieldId);
            String endOperator = operator;
            if (endFieldId.contains("|DE|")) {
                endOperator = multiple ? "in" : "eq";
                operator = endOperator;
            }
            out.add(filter(componentId, endFieldId, endOperator, resultEnd, parametersEnd, isTree));
        }
        if (fieldId.contains("|DE|")) {
            operator = multiple ? "in" : "eq";
        }
        out.add(filter(componentId, fieldId, operator, result, parametersFilter, isTree));
    }

    private JsonNode defaultSelectValue(ObjectNode item, int type, boolean multiple, boolean defaultValueCheck) {
        JsonNode defaultValue = item.get("defaultValue");
        if (type == 9) {
            if (!defaultValueCheck) {
                return JsonNodeFactory.instance.arrayNode();
            }
            if (multiple) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                if (defaultValue != null && defaultValue.isArray()) {
                    defaultValue.forEach(v -> arr.add(v.asText().replace("-de-", ",")));
                }
                return arr;
            }
            String v = defaultValue == null || defaultValue.isNull() ? "" : defaultValue.asText("");
            return JsonNodeFactory.instance.textNode(v.replace("-de-", ","));
        }
        int optionValueSource = item.path("optionValueSource").asInt(-1);
        JsonNode defaultMapValue = item.get("defaultMapValue");
        boolean hasMap = defaultMapValue != null && defaultMapValue.isArray() && !defaultMapValue.isEmpty();
        boolean hasDisplayId = StringUtils.isNotEmpty(item.path("displayId").asText(""));
        JsonNode empty = multiple ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.textNode("");
        if (optionValueSource == 1 && (hasMap || hasDisplayId) && type != 1 && type != 7) {
            return defaultValueCheck && defaultMapValue != null ? defaultMapValue : empty;
        }
        return defaultValueCheck && defaultValue != null && !defaultValue.isNull() ? defaultValue : empty;
    }

    private String operator(String displayType, int type, int conditionType, ObjectNode item) {
        if (type == 9) {
            return "in";
        }
        if (type == 22) {
            return "between";
        }
        if (StringUtils.equals(displayType, "8")) {
            String valueF = item.path("defaultConditionValueF").asText("");
            String valueS = item.path("defaultConditionValueS").asText("");
            String operatorF = item.path("defaultConditionValueOperatorF").asText("eq");
            String operatorS = item.path("defaultConditionValueOperatorS").asText("like");
            if (conditionType == 0) {
                return operatorF;
            }
            List<String> ops = new ArrayList<>();
            if (!valueF.isEmpty()) {
                ops.add(operatorF);
            }
            if (!valueS.isEmpty()) {
                ops.add(operatorS);
            }
            if (ops.size() == 2) {
                return String.join("-" + (conditionType == 1 ? "and" : "or") + "-", ops);
            }
            return valueF.isEmpty() ? operatorS : operatorF;
        }
        return type == 1 || type == 7 ? "between" : "in";
    }

    private static boolean hasValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isArray() || v.isTextual()) {
            return v.isArray() ? !v.isEmpty() : !v.asText().isEmpty();
        }
        return v.isNumber();
    }

    /**
     * forMatterValue：非时间类型原样转为数组；时间类型换算为起止毫秒
     */
    private List<String> formatValue(int type, JsonNode selectValue, String timeGranularity, String timeGranularityMultiple) {
        List<String> result = new ArrayList<>();
        if (type != 1 && type != 7) {
            if (selectValue.isArray()) {
                selectValue.forEach(v -> result.add(v.asText()));
            } else {
                result.add(selectValue.asText());
            }
            return result;
        }
        if (selectValue.isArray()) {
            List<LocalDateTime> values = new ArrayList<>();
            selectValue.forEach(v -> values.add(parseTime(v)));
            if (values.isEmpty() || values.contains(null)) {
                return result;
            }
            String unit = timeGranularityMultiple.split("range")[0];
            if ("datetimerange".equals(timeGranularityMultiple) || type == 1 || unit.isEmpty()) {
                values.forEach(v -> result.add(String.valueOf(millis(v))));
            } else if (timeGranularityMultiple.contains("range") && type == 7 && values.size() >= 2) {
                result.add(String.valueOf(millis(startOf(values.get(0), unit))));
                result.add(String.valueOf(millis(endOf(values.get(1), unit))));
            } else {
                result.add(String.valueOf(millis(values.get(0))));
                result.add(String.valueOf(millis(customTime(1, unit, unit, "b", null, timeGranularityMultiple, "start-config")) - 1000));
            }
            return result;
        }
        LocalDateTime v = parseTime(selectValue);
        if (v == null) {
            return result;
        }
        switch (timeGranularity) {
            case "year", "month", "date" -> {
                result.add(String.valueOf(millis(startOf(v, timeGranularity))));
                result.add(String.valueOf(millis(endOf(v, timeGranularity))));
            }
            case "datetime" -> {
                result.add(String.valueOf(millis(v)));
                result.add(String.valueOf(millis(v)));
            }
            default -> {
                return null;
            }
        }
        return result;
    }

    private record FieldResult(String fieldId, List<String> values) {
    }

    /**
     * 树形下拉：按图表关联的层级截取字段与值（getFieldId）
     */
    private FieldResult fieldId(JsonNode treeFieldList, List<String> result, List<Integer> relationshipChartIndex, String[] ids) {
        List<String> idArr = new ArrayList<>();
        treeFieldList.forEach(f -> idArr.add(f.path("id").asText()));
        if (result.isEmpty()) {
            return new FieldResult("", result);
        }
        String[] valArr = result.getLast().split(",", -1);
        if (relationshipChartIndex.isEmpty()) {
            return new FieldResult(String.join(",", idArr.subList(0, Math.min(valArr.length, idArr.size()))),
                    new ArrayList<>(new LinkedHashSet<>(result)));
        }
        List<Integer> indexArr = relationshipChartIndex.stream().filter(i -> i < valArr.length && !valArr[i].isEmpty()).toList();
        List<String> values = new ArrayList<>();
        for (String r : result) {
            String[] parts = r.split(",", -1);
            List<String> picked = new ArrayList<>();
            for (Integer i : indexArr) {
                picked.add(i < parts.length ? parts[i] : "");
            }
            String joined = String.join(",", picked);
            if (!joined.isEmpty() && !joined.endsWith(",")) {
                values.add(joined);
            }
        }
        List<String> fieldIds = new ArrayList<>();
        for (Integer i : indexArr) {
            fieldIds.add(i < ids.length ? ids[i] : "1");
        }
        return new FieldResult(String.join(",", fieldIds), values);
    }

    private List<JsonNode> distinctById(JsonNode params, String id) {
        List<JsonNode> list = new ArrayList<>();
        if (params == null || !params.isArray()) {
            return list;
        }
        for (JsonNode p : params) {
            if (StringUtils.equals(p.path("id").asText(), id)) {
                list.add(p);
                break;
            }
        }
        return list;
    }

    private ChartExtFilterDTO filter(Long componentId, String fieldId, String operator, List<String> values, List<JsonNode> parameters, boolean isTree) {
        ChartExtFilterDTO dto = new ChartExtFilterDTO();
        dto.setComponentId(componentId);
        dto.setFieldId(fieldId);
        dto.setOperator(operator);
        dto.setValue(new ArrayList<>(values));
        List<SqlVariableDetails> params = new ArrayList<>();
        for (JsonNode p : parameters) {
            try {
                params.add(MAPPER.treeToValue(p, SqlVariableDetails.class));
            } catch (Exception e) {
                LogUtil.warn("invalid filter parameter: " + e.getMessage());
            }
        }
        dto.setParameters(params);
        dto.setIsTree(isTree);
        dto.setFilterType(0);
        return dto;
    }

    // ------------------------------------------------------------------ 时间（time-format.ts / time-format-dayjs.ts）

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static long millis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static JsonNode longArray(Long[] values) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        if (values != null) {
            for (Long v : values) {
                if (v != null) {
                    arr.add(v);
                }
            }
        }
        return arr;
    }

    /**
     * 解析时间值：毫秒数、ISO 时间（含时区）、yyyy/MM/dd HH:mm:ss、yyyy-MM-dd、yyyy-MM、yyyy 等
     */
    static LocalDateTime parseTime(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(node.asLong()), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
        }
        String text = StringUtils.trimToEmpty(node.asText());
        if (text.isEmpty()) {
            return null;
        }
        if (StringUtils.isNumeric(text) && text.length() > 8) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(text)), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
        }
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(text).toInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
        } catch (Exception ignore) {
            // 非 ISO 带时区格式
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
        } catch (Exception ignore) {
            // 非 ISO 瞬时格式
        }
        try {
            return LocalDateTime.parse(text).truncatedTo(ChronoUnit.SECONDS);
        } catch (Exception ignore) {
            // 非 ISO 本地时间
        }
        for (DateTimeFormatter f : PATTERNS) {
            try {
                return LocalDateTime.parse(text, f);
            } catch (Exception ignore) {
                // 尝试下一个格式
            }
        }
        String normalized = text.replace('/', '-');
        try {
            if (normalized.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = normalized.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).atStartOfDay();
            }
            if (normalized.matches("\\d{4}-\\d{1,2}")) {
                String[] p = normalized.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 1).atStartOfDay();
            }
            if (normalized.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(normalized), 1, 1).atStartOfDay();
            }
        } catch (Exception ignore) {
            // 无法解析
        }
        return null;
    }

    static LocalDateTime startOf(LocalDateTime t, String unit) {
        return switch (StringUtils.defaultString(unit)) {
            case "year" -> t.toLocalDate().withDayOfYear(1).atStartOfDay();
            case "quarter" -> t.toLocalDate().with(IsoFields.DAY_OF_QUARTER, 1).atStartOfDay();
            case "month" -> t.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case "week" -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case "date", "day" -> t.toLocalDate().atStartOfDay();
            case "hour" -> t.truncatedTo(ChronoUnit.HOURS);
            case "minute" -> t.truncatedTo(ChronoUnit.MINUTES);
            default -> t.truncatedTo(ChronoUnit.SECONDS);
        };
    }

    /**
     * dayjs endOf 后格式化到秒：xx:59:59
     */
    static LocalDateTime endOf(LocalDateTime t, String unit) {
        LocalDateTime start = startOf(t, unit);
        LocalDateTime next = switch (StringUtils.defaultString(unit)) {
            case "year" -> start.plusYears(1);
            case "quarter" -> start.plusMonths(3);
            case "month" -> start.plusMonths(1);
            case "week" -> start.plusWeeks(1);
            case "date", "day" -> start.plusDays(1);
            case "hour" -> start.plusHours(1);
            case "minute" -> start.plusMinutes(1);
            default -> start.plusSeconds(1);
        };
        return next.minusSeconds(1);
    }

    static LocalDateTime plus(LocalDateTime t, long amount, String unit) {
        return switch (StringUtils.defaultString(unit)) {
            case "year" -> t.plusYears(amount);
            case "quarter" -> t.plusMonths(3 * amount);
            case "month" -> t.plusMonths(amount);
            case "week" -> t.plusWeeks(amount);
            case "date", "day" -> t.plusDays(amount);
            case "hour" -> t.plusHours(amount);
            case "minute" -> t.plusMinutes(amount);
            case "second" -> t.plusSeconds(amount);
            default -> t.plusDays(amount);
        };
    }

    /**
     * getCustomTime
     */
    static LocalDateTime customTime(int timeNum, String timeType, String timeGranularity, String around, LocalDateTime arbitraryTime,
                                    String timeGranularityMultiple, String sort) {
        long amount = "f".equals(around) ? -timeNum : timeNum;
        LocalDateTime result = plus(now(), amount, "date".equals(timeType) ? "day" : timeType);
        if (StringUtils.equalsAny(timeGranularityMultiple, "monthrange", "yearrange", "daterange")) {
            String unit = timeGranularityMultiple.split("range")[0];
            String[] parts = StringUtils.defaultString(sort).split("-");
            String direction = parts[0];
            String scene = parts.length > 1 ? parts[1] : "";
            if ("start".equals(direction)) {
                return startOf(result, unit);
            }
            if ("end".equals(direction)) {
                return "panel".equals(scene) ? endOf(result, unit) : result;
            }
            return result;
        }
        if (arbitraryTime != null) {
            return LocalDateTime.of(result.toLocalDate(), arbitraryTime.toLocalTime());
        }
        String unit = StringUtils.defaultString(timeGranularity).split("range")[0];
        return startOf(result, unit);
    }

    /**
     * getDynamicRange（单个时间的动态默认值）
     */
    private Long[] dynamicRange(ObjectNode item) {
        String relativeToCurrent = item.path("relativeToCurrent").asText("");
        String timeGranularity = item.path("timeGranularity").asText("date");
        int timeNum = item.path("timeNum").asInt(0);
        String relativeType = item.path("relativeToCurrentType").asText("date");
        String around = item.path("around").asText("f");
        long day = 24 * 3600 * 1000L;
        LocalDateTime now = now();
        if ("custom".equals(relativeToCurrent)) {
            LocalDateTime start = customTime(timeNum, relativeType, timeGranularity, around, null, null, null);
            LocalDateTime end = customTime(timeNum + ("f".equals(around) ? -1 : 1), relativeType, timeGranularity, around, null, null, null);
            return switch (timeGranularity) {
                case "year", "month" -> new Long[]{millis(start), millis(end) - 1000};
                case "date" -> new Long[]{millis(start), millis(start) + day - 1000};
                case "datetime" -> {
                    LocalDateTime v = customTime(timeNum, relativeType, timeGranularity, around, parseTime(item.get("arbitraryTime")), null, null);
                    yield new Long[]{millis(v), millis(v)};
                }
                default -> null;
            };
        }
        boolean isDateTime = "datetime".equals(timeGranularity);
        LocalDateTime today = startOf(now, "date");
        return switch (relativeToCurrent) {
            case "thisYear" -> new Long[]{millis(startOf(now, "year")), millis(startOf(now.plusYears(1), "year")) - 1000};
            case "lastYear" -> new Long[]{millis(startOf(now.minusYears(1), "year")), millis(startOf(now, "year")) - 1000};
            case "thisMonth" -> new Long[]{millis(startOf(now, "month")), millis(startOf(now.plusMonths(1), "month")) - 1000};
            case "lastMonth" -> new Long[]{millis(startOf(now.minusMonths(1), "month")), millis(startOf(now, "month")) - 1000};
            case "today" -> new Long[]{millis(today), isDateTime ? millis(today) : millis(today) + day - 1000};
            case "yesterday" -> {
                long y = millis(today.minusDays(1));
                yield new Long[]{y, isDateTime ? y : y + day - 1000};
            }
            case "monthBeginning" -> {
                long m = millis(startOf(now, "month"));
                yield new Long[]{m, isDateTime ? m : m + day - 1000};
            }
            case "monthEnd" -> {
                long e = millis(endOf(now, "month"));
                yield isDateTime ? new Long[]{e, e} : new Long[]{e - day + 1000, e};
            }
            case "yearBeginning" -> {
                long y = millis(startOf(now, "year"));
                yield new Long[]{y, isDateTime ? y : y + day - 1000};
            }
            default -> null;
        };
    }

    /**
     * getCustomRange（时间范围的快捷动态值）
     */
    static LocalDateTime[] customRange(String range) {
        LocalDateTime now = now();
        return switch (range) {
            case "thisYear" -> new LocalDateTime[]{startOf(now, "year"), endOf(now, "year")};
            case "lastYear" -> new LocalDateTime[]{startOf(now.minusYears(1), "year"), endOf(now.minusYears(1), "year")};
            case "thisMonth" -> new LocalDateTime[]{startOf(now, "month"), endOf(now, "month")};
            case "lastMonth", "LastMonthFull" -> new LocalDateTime[]{startOf(now.minusMonths(1), "month"), endOf(now.minusMonths(1), "month")};
            case "thisQuarter" -> new LocalDateTime[]{startOf(now, "quarter"), endOf(now, "quarter")};
            case "thisWeek" -> new LocalDateTime[]{startOf(now, "week"), endOf(now, "week")};
            case "LastThreeMonths" -> new LocalDateTime[]{startOf(now.minusMonths(2), "month"), endOf(now, "date")};
            case "LastSixMonths" -> new LocalDateTime[]{startOf(now.minusMonths(5), "month"), endOf(now, "date")};
            case "LastTwelveMonths" -> new LocalDateTime[]{startOf(now.minusMonths(11), "month"), endOf(now, "date")};
            case "YearToThisMonth" -> new LocalDateTime[]{startOf(now, "year"), endOf(now, "month")};
            case "YearToLastMonthEnd" -> {
                LocalDateTime start = startOf(now, "year");
                LocalDateTime end = endOf(now.minusMonths(1), "month");
                yield start.isAfter(end) ? new LocalDateTime[]{start, endOf(now, "date")} : new LocalDateTime[]{start, end};
            }
            case "monthToYesterday" -> {
                LocalDateTime start = startOf(now, "month");
                LocalDateTime end = endOf(now.minusDays(1), "date");
                yield start.isAfter(end) ? new LocalDateTime[]{start, endOf(now, "date")} : new LocalDateTime[]{start, end};
            }
            case "today" -> new LocalDateTime[]{startOf(now, "date"), endOf(now, "date")};
            case "yesterday" -> new LocalDateTime[]{startOf(now.minusDays(1), "date"), endOf(now.minusDays(1), "date")};
            case "LastThreeDays" -> new LocalDateTime[]{startOf(now.minusDays(2), "date"), endOf(now, "date")};
            case "monthBeginning" -> new LocalDateTime[]{startOf(now, "month"), endOf(now, "date")};
            case "yearBeginning" -> new LocalDateTime[]{startOf(now, "year"), endOf(now, "date")};
            default -> new LocalDateTime[]{now, now};
        };
    }

    // ------------------------------------------------------------------ 工具

    private static boolean contains(JsonNode array, String value) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode n : array) {
            if (StringUtils.equals(n.asText(), value)) {
                return true;
            }
        }
        return false;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(StringUtils.trimToEmpty(s));
        } catch (Exception e) {
            return -1;
        }
    }

    private static Long toLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
