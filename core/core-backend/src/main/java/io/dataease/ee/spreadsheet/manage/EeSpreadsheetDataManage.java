package io.dataease.ee.spreadsheet.manage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.api.permissions.dataset.dto.DataSetRowPermissionsTreeDTO;
import io.dataease.api.spreadsheet.dto.DetailTableQueryConfig;
import io.dataease.api.spreadsheet.dto.PluginFieldValuesRequest;
import io.dataease.api.spreadsheet.dto.PluginQueryDataConfig;
import io.dataease.api.spreadsheet.dto.PluginQueryRequest;
import io.dataease.api.spreadsheet.vo.DetailTableQueryDataResponse;
import io.dataease.api.spreadsheet.vo.PluginQueryResponse;
import io.dataease.api.spreadsheet.vo.SummaryQueryDataResponse;
import io.dataease.chart.manage.ChartFilterTreeService;
import io.dataease.chart.utils.ChartDataBuild;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.BusiResourceEnum;
import io.dataease.constant.DeTypeConstants;
import io.dataease.dataset.manage.DatasetGroupManage;
import io.dataease.dataset.manage.DatasetSQLManage;
import io.dataease.dataset.manage.PermissionManage;
import io.dataease.datasource.utils.DatasourceUtils;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.spreadsheet.SpreadsheetConstants;
import io.dataease.engine.constant.ExtFieldConstant;
import io.dataease.engine.func.FunctionConstant;
import io.dataease.engine.sql.SQLProvider;
import io.dataease.engine.trans.*;
import io.dataease.engine.utils.SQLUtils;
import io.dataease.engine.utils.Utils;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.api.PluginManageApi;
import io.dataease.extensions.datasource.dto.CalParam;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.datasource.dto.DatasourceRequest;
import io.dataease.extensions.datasource.dto.DatasourceSchemaDTO;
import io.dataease.extensions.datasource.dto.TableFieldWithValue;
import io.dataease.extensions.datasource.factory.ProviderFactory;
import io.dataease.extensions.datasource.model.SQLMeta;
import io.dataease.extensions.datasource.model.SQLObj;
import io.dataease.extensions.datasource.provider.Provider;
import io.dataease.extensions.view.dto.*;
import io.dataease.extensions.view.filter.FilterTreeItem;
import io.dataease.extensions.view.filter.FilterTreeObj;
import io.dataease.extensions.view.util.ChartDataUtil;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.BeanUtils;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电子表格明细表 / 透视表取数。
 *
 * <p>以当前用户身份经核心数据集 SQL 引擎（Provider / Calcite）取数：校验数据集查看权限（或通过可查看的电子表格访问），
 * 应用列权限（无权限列剔除、脱敏列脱敏）与行权限，字段定义一律取自数据集元数据，不信任前端传入的表达式。</p>
 */
@Component
public class EeSpreadsheetDataManage {

    private static final Set<String> SORTS = Set.of("asc", "desc", "custom_sort", "none");

    private static final Set<String> DATE_STYLES = Set.of("y", "y_Q", "y_M", "y_W", "y_M_d", "M_d", "H_m_s", "y_M_d_H", "y_M_d_H_m", "y_M_d_H_m_s");

    private static final Set<String> DATE_PATTERNS = Set.of("date_sub", "date_split");

    private static final Set<String> SUMMARIES = Set.of("sum", "avg", "max", "min", "count", "count_distinct");

    /**
     * 自定义总计表达式中允许出现的关键字（其余标识符必须是函数调用）
     */
    private static final Set<String> EXPRESSION_KEYWORDS = Set.of("case", "when", "then", "else", "end", "and", "or", "not",
            "null", "is", "in", "between", "like", "distinct", "as", "true", "false");

    private static final Set<String> FORBIDDEN_WORDS = Set.of("select", "insert", "update", "delete", "drop", "alter", "union",
            "exec", "execute", "into", "create", "truncate", "grant", "revoke", "sleep", "benchmark", "pg_sleep", "waitfor",
            "xp_cmdshell", "load_file", "outfile", "dumpfile", "from", "where", "having", "group", "order", "limit", "dbms_lock");

    private static final Pattern FIELD_REF = Pattern.compile("\\[([^\\]]*)]");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DatasetGroupManage datasetGroupManage;

    private final DatasetSQLManage datasetSQLManage;

    private final PermissionManage permissionManage;

    private final CorePermissionManage corePermissionManage;

    private final ChartFilterTreeService chartFilterTreeService;

    private final EeSettingManage settingManage;

    private final EeSpreadsheetManage spreadsheetManage;

    @Autowired(required = false)
    private PluginManageApi pluginManage;

    public EeSpreadsheetDataManage(DatasetGroupManage datasetGroupManage, DatasetSQLManage datasetSQLManage,
                                   PermissionManage permissionManage, CorePermissionManage corePermissionManage,
                                   ChartFilterTreeService chartFilterTreeService, EeSettingManage settingManage,
                                   EeSpreadsheetManage spreadsheetManage) {
        this.datasetGroupManage = datasetGroupManage;
        this.datasetSQLManage = datasetSQLManage;
        this.permissionManage = permissionManage;
        this.corePermissionManage = corePermissionManage;
        this.chartFilterTreeService = chartFilterTreeService;
        this.settingManage = settingManage;
        this.spreadsheetManage = spreadsheetManage;
    }

    /**
     * 系统参数 basic.spreadsheetQueryLimit，默认 100000
     */
    public long queryLimit() {
        String value = settingManage.get(SpreadsheetConstants.QUERY_LIMIT_KEY, null);
        if (StringUtils.isNotBlank(value)) {
            try {
                long limit = Long.parseLong(value.trim());
                if (limit > 0) {
                    return limit;
                }
            } catch (NumberFormatException ignored) {
                // 非法配置回退默认值
            }
        }
        return SpreadsheetConstants.DEFAULT_QUERY_LIMIT;
    }

    // ------------------------------------------------------------------ 取数

    public PluginQueryResponse queryData(PluginQueryRequest request) {
        if (request == null || request.getData() == null) {
            DEException.throwException(Translator.get("ee_sheet_dataset_required"));
        }
        String type = StringUtils.trimToEmpty(request.getType());
        if (!StringUtils.equalsAny(type, SpreadsheetConstants.TYPE_DETAIL, SpreadsheetConstants.TYPE_PIVOT)) {
            DEException.throwException(Translator.get("ee_sheet_type_invalid"));
        }
        try {
            QueryContext ctx = prepare(request.getData());
            return SpreadsheetConstants.TYPE_DETAIL.equals(type) ? queryDetail(ctx, request) : queryPivot(ctx);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("spreadsheet query failed: " + e.getMessage(), e);
            DEException.throwException(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * 字段候选值（自定义排序对话框），已应用行列权限与插件过滤条件
     */
    public List<String> queryFieldValues(PluginFieldValuesRequest request) {
        if (request == null || request.getData() == null) {
            DEException.throwException(Translator.get("ee_sheet_dataset_required"));
        }
        if (request.getField() == null || request.getField().getId() == null) {
            DEException.throwException(Translator.get("ee_sheet_field_invalid"));
        }
        try {
            QueryContext ctx = prepare(request.getData());
            List<ChartViewFieldDTO> fields = resolveFields(ctx, List.of(request.getField()), false);
            if (fields.isEmpty()) {
                DEException.throwException(Translator.get("ee_sheet_field_invalid"));
            }
            ChartViewFieldDTO field = fields.getFirst();
            field.setSort("asc");
            SQLMeta sqlMeta = baseSqlMeta(ctx);
            Dimension2SQLObj.dimension2sqlObj(sqlMeta, List.of(field), ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
            String sql = SQLProvider.createQuerySQL(sqlMeta, true, ctx.needOrder, limitView(ctx.limit));
            List<String[]> data = fetch(ctx, sqlMeta, sql);
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (String[] row : data) {
                if (row == null || row.length == 0 || row[0] == null) {
                    continue;
                }
                values.add(desensitize(ctx, field, row[0]));
            }
            return new ArrayList<>(values);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("spreadsheet query field values failed: " + e.getMessage(), e);
            DEException.throwException(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return null;
        }
    }

    private PluginQueryResponse queryDetail(QueryContext ctx, PluginQueryRequest request) throws Exception {
        Map<String, List<ChartViewFieldDTO>> zones = ctx.config.getZones();
        List<ChartViewFieldDTO> requested = zones == null ? null : zones.get("fields");
        if (CollectionUtils.isEmpty(requested)) {
            DEException.throwException(Translator.get("ee_sheet_no_fields"));
        }
        List<ChartViewFieldDTO> fields = resolveFields(ctx, requested, false);
        DetailTableQueryDataResponse data = new DetailTableQueryDataResponse();
        PluginQueryResponse response = new PluginQueryResponse();
        response.setData(data);
        data.setFields(slim(fields));
        data.setRowData(new ArrayList<>());
        data.setTotal(0L);
        data.setCustomTotalResult(new HashMap<>());
        if (fields.isEmpty()) {
            return response;
        }

        SQLMeta sqlMeta = baseSqlMeta(ctx);
        Dimension2SQLObj.dimension2sqlObj(sqlMeta, fields, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        for (int i = 0; i < fields.size(); i++) {
            // 聚合型计算字段不能出现在明细中
            if (fields.get(i).isAgg()) {
                sqlMeta.getXFields().get(i).setFieldName("'-'");
            }
        }
        String querySql = SQLProvider.createQuerySQL(sqlMeta, false, ctx.needOrder, limitView(ctx.limit));
        List<String[]> rows = fetch(ctx, sqlMeta, querySql);
        rows = ChartDataUtil.resultCustomSort(fields, new ArrayList<>(), new ArrayList<>(), rows);
        data.setRowData(toRows(ctx, fields, rows));
        response.setSql(Base64.getEncoder().encodeToString(StringUtils.defaultString(ctx.lastSql).getBytes()));

        long total = rows.size();
        if (total >= ctx.limit) {
            String countSql = "SELECT COUNT(*) FROM (" + SQLProvider.createQuerySQLNoSort(sqlMeta, false, new ChartViewDTO()) + ") COUNT_TEMP";
            List<String[]> countData = fetch(ctx, sqlMeta, countSql);
            if (CollectionUtils.isNotEmpty(countData) && countData.getFirst().length > 0 && StringUtils.isNumeric(countData.getFirst()[0])) {
                total = Long.parseLong(countData.getFirst()[0]);
            }
        }
        data.setTotal(total);
        data.setCustomTotalResult(customTotals(ctx, sqlMeta, fields, request.getQueryConfig()));
        return response;
    }

    private PluginQueryResponse queryPivot(QueryContext ctx) throws Exception {
        Map<String, List<ChartViewFieldDTO>> zones = ctx.config.getZones() == null ? Map.of() : ctx.config.getZones();
        List<ChartViewFieldDTO> rows = resolveFields(ctx, zones.getOrDefault("rows", List.of()), true);
        List<ChartViewFieldDTO> columns = resolveFields(ctx, zones.getOrDefault("columns", List.of()), true);
        List<ChartViewFieldDTO> rowDims = rows.stream().filter(f -> !isQuota(f)).filter(f -> !f.isAgg()).toList();
        List<ChartViewFieldDTO> colDims = columns.stream().filter(f -> !isQuota(f)).filter(f -> !f.isAgg()).toList();
        List<ChartViewFieldDTO> quotas = new ArrayList<>();
        rows.stream().filter(this::isQuota).forEach(quotas::add);
        columns.stream().filter(this::isQuota).forEach(quotas::add);
        List<ChartViewFieldDTO> dims = new ArrayList<>(rowDims);
        dims.addAll(colDims);

        SummaryQueryDataResponse data = new SummaryQueryDataResponse();
        PluginQueryResponse response = new PluginQueryResponse();
        response.setData(data);
        data.setRowFields(slim(rowDims));
        data.setColumnFields(slim(colDims));
        data.setQuotaFields(slim(quotas));
        List<ChartViewFieldDTO> all = new ArrayList<>(dims);
        all.addAll(quotas);
        data.setFields(slim(all));
        data.setRowData(new ArrayList<>());
        data.setTotal(0L);
        if (dims.isEmpty() && quotas.isEmpty()) {
            return response;
        }

        SQLMeta sqlMeta = baseSqlMeta(ctx);
        Dimension2SQLObj.dimension2sqlObj(sqlMeta, dims, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        Quota2SQLObj.quota2sqlObj(sqlMeta, quotas, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        String querySql = SQLProvider.createQuerySQL(sqlMeta, true, ctx.needOrder, limitView(ctx.limit));
        List<String[]> result = fetch(ctx, sqlMeta, querySql);
        result = ChartDataUtil.resultCustomSort(dims, quotas, new ArrayList<>(), result);
        data.setRowData(toRows(ctx, all, result));
        data.setTotal((long) result.size());
        response.setSql(Base64.getEncoder().encodeToString(StringUtils.defaultString(ctx.lastSql).getBytes()));
        return response;
    }

    /**
     * 自定义总计：在相同过滤条件下脱离明细维度单独聚合
     */
    private Map<String, BigDecimal> customTotals(QueryContext ctx, SQLMeta sqlMeta, List<ChartViewFieldDTO> fields, Map<String, Object> queryConfig) throws Exception {
        Map<String, BigDecimal> result = new HashMap<>();
        if (queryConfig == null || queryConfig.get("totalFields") == null) {
            return result;
        }
        DetailTableQueryConfig config = JsonUtil.parseObject(JsonUtil.toJSONString(queryConfig).toString(), DetailTableQueryConfig.class);
        if (config == null || CollectionUtils.isEmpty(config.getTotalFields())) {
            return result;
        }
        Set<String> names = new HashSet<>();
        fields.forEach(f -> names.add(f.getDataeaseName()));
        List<ChartViewFieldDTO> calcFields = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (TableCalcTotalCfg cfg : config.getTotalFields()) {
            if (cfg == null || !StringUtils.equalsIgnoreCase(cfg.getAggregation(), "CUSTOM") || StringUtils.isBlank(cfg.getOriginName())
                    || !names.contains(cfg.getDataeaseName()) || keys.contains(cfg.getDataeaseName())) {
                continue;
            }
            validateExpression(ctx, cfg.getOriginName());
            ChartViewFieldDTO calc = new ChartViewFieldDTO();
            calc.setId(IDUtils.snowID());
            calc.setOriginName(cfg.getOriginName());
            calc.setName(cfg.getDataeaseName());
            calc.setDataeaseName(cfg.getDataeaseName());
            calc.setExtField(ExtFieldConstant.EXT_CALC);
            calc.setDeType(DeTypeConstants.DE_FLOAT);
            calc.setGroupType("q");
            calc.setSummary("");
            calcFields.add(calc);
            keys.add(cfg.getDataeaseName());
        }
        if (calcFields.isEmpty()) {
            return result;
        }
        sqlMeta.setXFields(Collections.emptyList());
        sqlMeta.setXOrders(Collections.emptyList());
        // 表达式只能引用当前用户有权限的字段
        List<DatasetTableFieldDTO> originFields = copyFields(ctx.permittedFields);
        calcFields.forEach(c -> originFields.add(BeanUtils.copyBean(new DatasetTableFieldDTO(), c)));
        Quota2SQLObj.quota2sqlObj(sqlMeta, calcFields, originFields, ctx.crossDs, ctx.dsMap, Utils.getParams(originFields), null, pluginManage);
        String sql = SQLProvider.createQuerySQL(sqlMeta, false, false, new ChartViewDTO());
        List<String[]> data = fetch(ctx, sqlMeta, sql);
        if (CollectionUtils.isNotEmpty(data)) {
            String[] row = data.getFirst();
            for (int i = 0; i < keys.size() && i < row.length; i++) {
                String key = keys.get(i);
                if (ctx.desensitization.containsKey(key)) {
                    result.put(key, null);
                    continue;
                }
                try {
                    result.put(key, row[i] == null ? null : new BigDecimal(row[i]));
                } catch (NumberFormatException e) {
                    result.put(key, null);
                }
            }
        }
        return result;
    }

    /**
     * 校验自定义总计表达式：字段引用必须是有权限的字段；除函数调用与少量关键字外不允许出现其它标识符（防止直接引用无权限列或注入）
     */
    private void validateExpression(QueryContext ctx, String expression) {
        Set<String> permittedIds = new HashSet<>();
        ctx.permittedFields.forEach(f -> permittedIds.add(String.valueOf(f.getId())));
        Matcher refMatcher = FIELD_REF.matcher(expression);
        while (refMatcher.find()) {
            if (!permittedIds.contains(refMatcher.group(1))) {
                DEException.throwException(Translator.get("ee_sheet_custom_total_invalid"));
            }
        }
        String text = FIELD_REF.matcher(expression).replaceAll(" 0 ");
        if (StringUtils.countMatches(text, '\'') % 2 != 0 || StringUtils.containsAny(text, "\"`;#\\") || text.contains("--")
                || text.contains("/*") || text.contains("*/")) {
            DEException.throwException(Translator.get("ee_sheet_custom_total_invalid"));
        }
        String withoutStrings = text.replaceAll("'[^']*'", " '' ");
        Matcher matcher = IDENTIFIER.matcher(withoutStrings);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_WORDS.contains(word)) {
                DEException.throwException(Translator.get("ee_sheet_custom_total_invalid"));
            }
            if (EXPRESSION_KEYWORDS.contains(word)) {
                continue;
            }
            String rest = withoutStrings.substring(matcher.end()).stripLeading();
            if (!rest.startsWith("(")) {
                DEException.throwException(Translator.get("ee_sheet_custom_total_invalid"));
            }
        }
    }

    // ------------------------------------------------------------------ 公共步骤

    private QueryContext prepare(PluginQueryDataConfig config) throws Exception {
        Long datasetId = config.getDatasetId();
        if (datasetId == null) {
            DEException.throwException(Translator.get("ee_sheet_dataset_required"));
        }
        DatasetGroupInfoDTO table = datasetGroupManage.getDatasetGroupInfoDTO(datasetId, null);
        if (table == null || !StringUtils.equalsIgnoreCase(table.getNodeType(), "dataset")) {
            DEException.throwException(Translator.get("i18n_no_ds"));
        }
        boolean readable = corePermissionManage.checkAuth(new BusiPerCheckDTO(datasetId, BusiResourceEnum.DATASET, AuthEnum.READ))
                || spreadsheetManage.datasetReadableThroughSpreadsheet(datasetId);
        if (!readable) {
            DEException.throwException(Translator.get("i18n_no_dataset_permission"));
        }
        Long uid = V3UserUtil.getUid();
        QueryContext ctx = new QueryContext();
        ctx.config = config;
        ctx.table = table;
        ctx.allFields = CollectionUtils.isEmpty(table.getAllFields()) ? new ArrayList<>() : copyFields(table.getAllFields());
        if (ctx.allFields.isEmpty()) {
            DEException.throwException(Translator.get("i18n_no_fields"));
        }
        ctx.params = Utils.getParams(ctx.allFields);
        ctx.desensitization = new HashMap<>();
        ctx.permittedFields = permissionManage.filterColumnPermissions(copyFields(ctx.allFields), ctx.desensitization, datasetId, uid);
        if (ctx.permittedFields.isEmpty()) {
            DEException.throwException(Translator.get("i18n_no_column_permission"));
        }
        ctx.rowPermissions = permissionManage.getRowPermissionsTree(datasetId, uid);
        ctx.aggIds = aggFieldIds(ctx.allFields);
        long maxLimit = queryLimit();
        Long resultLimit = config.getResultLimit();
        long limit = resultLimit == null || resultLimit <= 0 ? SpreadsheetConstants.DEFAULT_RESULT_LIMIT : resultLimit;
        ctx.limit = Math.min(limit, maxLimit);

        Map<Long, DatasetTableFieldDTO> fieldMap = new HashMap<>();
        ctx.allFields.forEach(f -> fieldMap.put(f.getId(), f));
        ctx.fieldMap = fieldMap;

        // 过滤条件：插件自定义过滤 + 查询组件条件（树/文本搜索为过滤树，其余为普通条件）
        List<FilterTreeObj> trees = new ArrayList<>();
        if (config.getCustomFilter() != null) {
            trees.add(config.getCustomFilter());
        }
        List<ChartExtFilterDTO> extFilters = new ArrayList<>();
        List<ChartExtFilterDTO> paramFilters = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(config.getQueryFilter())) {
            for (ChartExtFilterDTO filter : config.getQueryFilter()) {
                if (filter == null) {
                    continue;
                }
                if (filter.getCustomFilter() != null) {
                    trees.add(filter.getCustomFilter());
                    continue;
                }
                if (CollectionUtils.isNotEmpty(filter.getParameters())) {
                    paramFilters.add(filter);
                }
                extFilters.addAll(toExtFilters(ctx, filter));
            }
        }
        ctx.customFilter = mergeTrees(ctx, trees);
        ctx.extFilters = extFilters;

        ChartExtRequest chartExtRequest = new ChartExtRequest();
        chartExtRequest.setUser(uid);
        chartExtRequest.setFilter(paramFilters);
        Map<String, Object> sqlMap = datasetSQLManage.getUnionSQLForEdit(table, chartExtRequest);
        String sql = (String) sqlMap.get("sql");
        @SuppressWarnings("unchecked")
        Map<Long, DatasourceSchemaDTO> dsMap = (Map<Long, DatasourceSchemaDTO>) sqlMap.get("dsMap");
        if (dsMap == null || dsMap.isEmpty()) {
            DEException.throwException(Translator.get("i18n_datasource_delete"));
        }
        DatasourceUtils.checkDsStatus(dsMap);
        ctx.crossDs = Boolean.TRUE.equals(table.getIsCross());
        if (!ctx.crossDs) {
            sql = Utils.replaceSchemaAlias(sql, dsMap);
        }
        ctx.sql = sql;
        ctx.sqlMap = sqlMap;
        ctx.dsMap = dsMap;
        String dsType = dsMap.values().iterator().next().getType();
        ctx.needOrder = !StringUtils.equalsIgnoreCase(dsType, "es");
        ctx.provider = ctx.crossDs ? ProviderFactory.getDefaultProvider() : ProviderFactory.getProvider(dsType);
        return ctx;
    }

    private SQLMeta baseSqlMeta(QueryContext ctx) {
        SQLMeta sqlMeta = new SQLMeta();
        Table2SQLObj.table2sqlobj(sqlMeta, null, "(" + ctx.sql + ")", ctx.crossDs);
        CustomWhere2Str.customWhere2sqlObj(sqlMeta, ctx.customFilter, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        ExtWhere2Str.extWhere2sqlOjb(sqlMeta, ctx.extFilters, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        WhereTree2Str.transFilterTrees(sqlMeta, ctx.rowPermissions, ctx.allFields, ctx.crossDs, ctx.dsMap, ctx.params, null, pluginManage);
        return sqlMeta;
    }

    @SuppressWarnings("unchecked")
    private List<String[]> fetch(QueryContext ctx, SQLMeta sqlMeta, String sql) throws Exception {
        String querySql = ctx.provider.rebuildSQL(sql, sqlMeta, ctx.crossDs, ctx.dsMap);
        ctx.lastSql = querySql;
        DatasourceRequest datasourceRequest = new DatasourceRequest();
        datasourceRequest.setQuery(querySql);
        datasourceRequest.setDsList(ctx.dsMap);
        datasourceRequest.setIsCross(ctx.crossDs);
        List<TableFieldWithValue> tableFieldWithValues = (List<TableFieldWithValue>) ctx.sqlMap.get("tableFieldWithValues");
        if (CollectionUtils.isNotEmpty(tableFieldWithValues)) {
            datasourceRequest.setTableFieldWithValues(tableFieldWithValues.stream().map(TableFieldWithValue::copy).toList());
        }
        LogUtil.debug("spreadsheet query sql: " + querySql);
        Map<String, Object> result = ctx.provider.fetchResultField(datasourceRequest);
        List<String[]> data = result == null ? null : (List<String[]>) result.get("data");
        return data == null ? new ArrayList<>() : new ArrayList<>(data);
    }

    /**
     * 以数据集元数据重建字段，仅叠加前端的展示/排序/汇总配置；无列权限或已删除的字段被剔除
     */
    private List<ChartViewFieldDTO> resolveFields(QueryContext ctx, List<ChartViewFieldDTO> requested, boolean pivot) {
        List<ChartViewFieldDTO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(requested)) {
            return result;
        }
        Map<Long, DatasetTableFieldDTO> permitted = new HashMap<>();
        ctx.permittedFields.forEach(f -> permitted.put(f.getId(), f));
        Set<Long> used = new HashSet<>();
        for (ChartViewFieldDTO req : requested) {
            if (req == null || req.getId() == null || !used.add(req.getId())) {
                continue;
            }
            DatasetTableFieldDTO base = permitted.get(req.getId());
            if (base == null) {
                continue;
            }
            ChartViewFieldDTO field = BeanUtils.copyBean(new ChartViewFieldDTO(), base);
            field.setParams(base.getParams());
            field.setGroupList(base.getGroupList());
            String groupType = StringUtils.equalsIgnoreCase(req.getGroupType(), "q") ? "q"
                    : StringUtils.equalsIgnoreCase(req.getGroupType(), "d") ? "d" : base.getGroupType();
            field.setGroupType(groupType);
            field.setChartShowName(req.getChartShowName());
            field.setSort(in(SORTS, req.getSort()) ? req.getSort() : "none");
            field.setCustomSort(req.getCustomSort());
            field.setDateStyle(in(DATE_STYLES, req.getDateStyle()) ? req.getDateStyle() : null);
            field.setDatePattern(in(DATE_PATTERNS, req.getDatePattern()) ? req.getDatePattern() : null);
            field.setFormatterCfg(req.getFormatterCfg());
            field.setFilter(null);
            field.setDesensitized(ctx.desensitization.containsKey(base.getDataeaseName()));
            boolean agg = ctx.aggIds.contains(base.getId());
            field.setAgg(agg);
            if (pivot && "q".equals(groupType)) {
                String summary = StringUtils.lowerCase(req.getSummary());
                field.setSummary(agg ? "" : (in(SUMMARIES, summary) ? summary : "sum"));
            } else {
                field.setSummary(agg ? "" : null);
            }
            result.add(field);
        }
        return result;
    }

    private static boolean in(Set<String> set, String value) {
        return value != null && set.contains(value);
    }

    private boolean isQuota(ChartViewFieldDTO field) {
        return StringUtils.equalsIgnoreCase(field.getGroupType(), "q");
    }

    private List<ChartExtFilterDTO> toExtFilters(QueryContext ctx, ChartExtFilterDTO filter) {
        List<ChartExtFilterDTO> result = new ArrayList<>();
        if (StringUtils.isBlank(filter.getFieldId()) || StringUtils.isBlank(filter.getOperator())) {
            return result;
        }
        String operator = StringUtils.lowerCase(filter.getOperator().trim());
        if (!StringUtils.equalsAny(operator, "in", "not in", "between", "eq", "not_eq", "like", "not like", "lt", "le", "gt", "ge", "null", "not_null")) {
            return result;
        }
        for (String part : filter.getFieldId().split(",")) {
            String text = part.trim();
            if (!StringUtils.isNumeric(text)) {
                continue;
            }
            DatasetTableFieldDTO field = ctx.fieldMap.get(Long.valueOf(text));
            if (field == null) {
                continue;
            }
            ChartExtFilterDTO item = new ChartExtFilterDTO();
            item.setFieldId(text);
            item.setOperator(operator);
            item.setIsTree(false);
            item.setFilterType(0);
            item.setDatasetTableField(field);
            item.setDateStyle(filter.getDateStyle());
            item.setDatePattern(filter.getDatePattern());
            if (filter.getValue() != null) {
                item.setOriginValue(new ArrayList<>(filter.getValue()));
                item.setValue(filter.getValue().stream().map(SQLUtils::transKeyword).toList());
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 合并多个过滤树（AND），字段只能引用本数据集字段，逻辑运算符限定为 and/or
     */
    private FilterTreeObj mergeTrees(QueryContext ctx, List<FilterTreeObj> trees) {
        List<FilterTreeItem> items = new ArrayList<>();
        for (FilterTreeObj tree : trees) {
            FilterTreeObj sanitized = sanitizeTree(ctx, tree, 0);
            if (sanitized != null) {
                FilterTreeItem item = new FilterTreeItem();
                item.setType("tree");
                item.setSubTree(sanitized);
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        FilterTreeObj merged = new FilterTreeObj();
        merged.setLogic("and");
        merged.setItems(items);
        return chartFilterTreeService.charReplace(merged);
    }

    private FilterTreeObj sanitizeTree(QueryContext ctx, FilterTreeObj tree, int depth) {
        if (tree == null || CollectionUtils.isEmpty(tree.getItems()) || depth > 20) {
            return null;
        }
        List<FilterTreeItem> items = new ArrayList<>();
        for (FilterTreeItem item : tree.getItems()) {
            if (item == null) {
                continue;
            }
            if (StringUtils.equalsIgnoreCase(item.getType(), "tree") || (item.getSubTree() != null && item.getFieldId() == null)) {
                FilterTreeObj sub = sanitizeTree(ctx, item.getSubTree(), depth + 1);
                if (sub != null) {
                    item.setType("tree");
                    item.setSubTree(sub);
                    items.add(item);
                }
                continue;
            }
            DatasetTableFieldDTO field = item.getFieldId() == null ? null : ctx.fieldMap.get(item.getFieldId());
            if (field == null) {
                continue;
            }
            item.setType("item");
            item.setField(field);
            items.add(item);
        }
        if (items.isEmpty()) {
            return null;
        }
        FilterTreeObj result = new FilterTreeObj();
        result.setLogic(StringUtils.equalsIgnoreCase(tree.getLogic(), "or") ? "or" : "and");
        result.setItems(items);
        return result;
    }

    private Set<Long> aggFieldIds(List<DatasetTableFieldDTO> fields) {
        Set<Long> result = new HashSet<>();
        SQLObj tableObj = new SQLObj();
        tableObj.setTableAlias("");
        for (DatasetTableFieldDTO field : fields) {
            if (!Objects.equals(field.getExtField(), ExtFieldConstant.EXT_CALC)) {
                continue;
            }
            try {
                String origin = Utils.calcFieldRegex(field, tableObj, fields, true, null, Utils.mergeParam(Utils.getParams(fields), null), pluginManage);
                for (String func : FunctionConstant.AGG_FUNC) {
                    if (Utils.matchFunction(func, origin)) {
                        result.add(field.getId());
                        break;
                    }
                }
            } catch (Exception e) {
                LogUtil.warn("parse calc field failed: " + e.getMessage());
            }
        }
        return result;
    }

    private List<ObjectNode> toRows(QueryContext ctx, List<ChartViewFieldDTO> fields, List<String[]> data) {
        List<ObjectNode> rows = new ArrayList<>(data.size());
        for (String[] item : data) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            for (int i = 0; i < fields.size(); i++) {
                ChartViewFieldDTO field = fields.get(i);
                String key = field.getDataeaseName();
                String value = item != null && i < item.length ? item[i] : null;
                if (ctx.desensitization.containsKey(key)) {
                    node.put(key, desensitize(ctx, field, value));
                    continue;
                }
                Integer deType = field.getDeType();
                boolean numeric = Objects.equals(deType, DeTypeConstants.DE_INT) || Objects.equals(deType, DeTypeConstants.DE_FLOAT)
                        || Objects.equals(deType, DeTypeConstants.DE_BOOL);
                if (isQuota(field) && (numeric || StringUtils.containsIgnoreCase(field.getSummary(), "count"))) {
                    if (StringUtils.isBlank(value)) {
                        node.putNull(key);
                    } else {
                        try {
                            node.put(key, new BigDecimal(value.trim()));
                        } catch (NumberFormatException e) {
                            node.put(key, value);
                        }
                    }
                } else {
                    node.put(key, value == null ? "" : value);
                }
            }
            rows.add(node);
        }
        return rows;
    }

    private String desensitize(QueryContext ctx, ChartViewFieldDTO field, String value) {
        ColumnPermissionItem item = ctx.desensitization.get(field.getDataeaseName());
        return item == null ? value : ChartDataBuild.desensitizationValue(item, String.valueOf(value));
    }

    /**
     * 返回给前端的字段信息（不包含表达式等内部定义）
     */
    private List<ChartViewFieldDTO> slim(List<ChartViewFieldDTO> fields) {
        List<ChartViewFieldDTO> result = new ArrayList<>();
        for (ChartViewFieldDTO f : fields) {
            ChartViewFieldDTO vo = new ChartViewFieldDTO();
            vo.setId(f.getId());
            vo.setName(f.getName());
            vo.setChartShowName(f.getChartShowName());
            vo.setGroupType(f.getGroupType());
            vo.setDeType(f.getDeType());
            vo.setDeExtractType(f.getDeExtractType());
            vo.setExtField(f.getExtField());
            vo.setSort(f.getSort());
            vo.setCustomSort(f.getCustomSort());
            vo.setDataeaseName(f.getDataeaseName());
            vo.setSummary(f.getSummary());
            vo.setDateStyle(f.getDateStyle());
            vo.setDatePattern(f.getDatePattern());
            vo.setDatasetGroupId(f.getDatasetGroupId());
            vo.setDesensitized(f.getDesensitized());
            result.add(vo);
        }
        return result;
    }

    private static ChartViewDTO limitView(long limit) {
        ChartViewDTO view = new ChartViewDTO();
        view.setType("spreadsheet");
        view.setResultMode("custom");
        view.setResultCount((int) Math.min(limit, Integer.MAX_VALUE));
        return view;
    }

    private static List<DatasetTableFieldDTO> copyFields(List<DatasetTableFieldDTO> fields) {
        List<DatasetTableFieldDTO> result = new ArrayList<>(fields.size());
        for (DatasetTableFieldDTO field : fields) {
            DatasetTableFieldDTO copy = BeanUtils.copyBean(new DatasetTableFieldDTO(), field);
            copy.setParams(field.getParams());
            copy.setGroupList(field.getGroupList());
            result.add(copy);
        }
        return result;
    }

    private static class QueryContext {
        PluginQueryDataConfig config;
        DatasetGroupInfoDTO table;
        List<DatasetTableFieldDTO> allFields;
        List<DatasetTableFieldDTO> permittedFields;
        Map<Long, DatasetTableFieldDTO> fieldMap;
        Map<String, ColumnPermissionItem> desensitization;
        List<DataSetRowPermissionsTreeDTO> rowPermissions;
        Set<Long> aggIds;
        List<CalParam> params;
        FilterTreeObj customFilter;
        List<ChartExtFilterDTO> extFilters;
        String sql;
        Map<String, Object> sqlMap;
        Map<Long, DatasourceSchemaDTO> dsMap;
        boolean crossDs;
        boolean needOrder;
        Provider provider;
        long limit;
        String lastSql;
    }
}
