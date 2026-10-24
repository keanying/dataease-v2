package io.dataease.ee.datafilling.manage;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.xpack.dataFilling.dto.*;
import io.dataease.constant.XpackSettingConstants;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.datafilling.DfConstants;
import io.dataease.ee.datafilling.dao.entity.DfCommitRecord;
import io.dataease.ee.datafilling.dao.entity.DfForm;
import io.dataease.ee.datafilling.dao.entity.QDfCommitRecord;
import io.dataease.ee.datafilling.dao.repo.DfCommitRecordRepository;
import io.dataease.ee.datafilling.jdbc.DfJdbcSupport;
import io.dataease.ee.datafilling.jdbc.DfSession;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtFormSettings;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.extensions.datafilling.dto.ExtraColumnItem;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datasource.dto.TableFieldWithValue;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.system.manage.SysParameterManage;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;

/**
 * 数据填报表内数据：查询、增删改、选项、提交日志
 */
@Component
public class DfDataManage {

    private static final int OPTION_LIMIT = 2000;

    private final DfAccess access;

    private final DfJdbcSupport jdbcSupport;

    private final DfCommitRecordRepository logRepository;

    private final DfUsers users;

    private final JPAQueryFactory queryFactory;

    private final EePermissionManage permissionManage;

    private final SysParameterManage sysParameterManage;

    public DfDataManage(DfAccess access, DfJdbcSupport jdbcSupport, DfCommitRecordRepository logRepository, DfUsers users,
                        JPAQueryFactory queryFactory, EePermissionManage permissionManage, @Lazy SysParameterManage sysParameterManage) {
        this.access = access;
        this.jdbcSupport = jdbcSupport;
        this.logRepository = logRepository;
        this.users = users;
        this.queryFactory = queryFactory;
        this.permissionManage = permissionManage;
        this.sysParameterManage = sysParameterManage;
    }

    public DfFormMeta meta(Long formId) {
        DfForm form = access.form(formId);
        if (!StringUtils.equals(form.getNodeType(), DfConstants.NODE_FORM)) {
            DEException.throwException(Translator.get("ee_df_form_not_exists"));
        }
        return new DfFormMeta(form);
    }

    // ------------------------------------------------------------------ query

    public DataFillFormTableDataResponse tableData(Long formId, DataFillFormTableDataRequest request) {
        DfForm form = access.form(formId);
        if (!access.can(formId, EeConstants.WEIGHT_READ)) {
            List<String> pks = request.getPrimaryKeyValueList();
            if (CollectionUtils.isEmpty(pks) || !access.ownsRows(formId, pks)) {
                access.deny();
            }
        }
        return query(new DfFormMeta(form), request);
    }

    public DataFillFormTableDataResponse query(DfFormMeta meta, DataFillFormTableDataRequest request) {
        DataFillFormTableDataResponse response = new DataFillFormTableDataResponse();
        String pk = meta.pk();
        response.setKey(pk);
        long pageSize = Math.max(request.getPageSize(), 0);
        long currentPage = Math.max(request.getCurrentPage(), 1);
        response.setCurrentPage(currentPage);
        response.setPageSize(pageSize);
        if (request.getPrimaryKeyValueList() != null && request.getPrimaryKeyValueList().isEmpty()) {
            response.setData(new ArrayList<>());
            return response;
        }
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            Map<String, Integer> types = session.columnTypes(meta.table());
            List<Object> params = new ArrayList<>();
            String where = where(session, meta, types, request.getSearchParams(), request.getPrimaryKeyValueList(), params);
            String from = " FROM " + session.table(meta.table()) + where;
            long total = session.count("SELECT COUNT(1)" + from, params);
            response.setTotal(total);
            List<String> select = new ArrayList<>();
            select.add(session.q(pk));
            for (DfFormMeta.Column c : meta.columns()) {
                if (types.containsKey(c.name().toLowerCase())) {
                    select.add(session.q(c.name()));
                }
            }
            String sql = "SELECT " + String.join(", ", select) + from + " ORDER BY " + session.q(pk) + " DESC";
            sql = session.dialect().page(sql, pageSize, (currentPage - 1) * pageSize);
            List<Map<String, Object>> rows = normalize(meta, session.query(sql, params));
            if (request.isWithoutLogs()) {
                response.setData(rows);
            } else {
                Map<String, DfCommitLog> logs = latestLogs(meta.form().getId(),
                        rows.stream().map(r -> (String) r.get(pk)).filter(Objects::nonNull).toList());
                List<Map<String, Object>> wrapped = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("data", row);
                    DfCommitLog log = logs.get((String) row.get(pk));
                    item.put("logInfo", log == null ? null : DfJson.write(log));
                    wrapped.add(item);
                }
                response.setData(wrapped);
            }
        }
        return response;
    }

    /**
     * 列名按表单定义的大小写返回，主键统一为字符串
     */
    private List<Map<String, Object>> normalize(DfFormMeta meta, List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (meta.isPk(e.getKey())) {
                    m.put(meta.pk(), e.getValue() == null ? null : String.valueOf(e.getValue()));
                    continue;
                }
                DfFormMeta.Column c = meta.column(e.getKey());
                String key = c == null ? e.getKey() : c.name();
                Object v = e.getValue();
                // Oracle 的 NUMBER(19) 读出为 BigDecimal，整数列先转为 long
                if (c != null && c.type() == ExtTableField.BaseType.number && v instanceof Number n) {
                    v = n.longValue();
                } else if (v instanceof BigDecimal d && c != null && c.type() != ExtTableField.BaseType.decimal) {
                    v = d.stripTrailingZeros().toPlainString();
                }
                m.put(key, v);
            }
            result.add(m);
        }
        return result;
    }

    private String where(DfSession session, DfFormMeta meta, Map<String, Integer> types, List<DataFillFormTableDataSearchParam> searchParams,
                         List<String> pks, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(pks)) {
            Integer pkType = types.get(meta.pk().toLowerCase());
            List<String> marks = new ArrayList<>();
            for (String pk : pks) {
                marks.add("?");
                params.add(DfValues.pkValue(pk, pkType));
            }
            conditions.add(session.q(meta.pk()) + " IN (" + String.join(", ", marks) + ")");
        }
        if (CollectionUtils.isNotEmpty(searchParams)) {
            for (DataFillFormTableDataSearchParam p : searchParams) {
                if (p == null || StringUtils.isBlank(p.getField())) {
                    continue;
                }
                String term = StringUtils.defaultIfBlank(p.getTerm(), "eq");
                ExtTableField.BaseType type;
                String column;
                if (meta.isPk(p.getField())) {
                    column = meta.pk();
                    type = ExtTableField.BaseType.nvarchar;
                } else {
                    DfFormMeta.Column c = meta.column(p.getField());
                    if (c == null) {
                        continue;
                    }
                    column = c.name();
                    type = c.type();
                }
                String col = session.q(column);
                Integer jdbcType = types.get(column.toLowerCase());
                String cmp = session.dialect().comparable(col, jdbcType);
                switch (term) {
                    case "null" -> conditions.add(col + " IS NULL");
                    case "not_null" -> conditions.add(col + " IS NOT NULL");
                    case "empty" -> conditions.add(session.dialect().emptyCondition(col, jdbcType));
                    case "not_empty" -> conditions.add(session.dialect().notEmptyCondition(col, jdbcType));
                    case "like", "not like", "not_like" -> {
                        if (DfValues.isEmpty(p.getValue())) {
                            continue;
                        }
                        conditions.add(col + (term.equals("like") ? " LIKE ?" : " NOT LIKE ?"));
                        params.add("%" + p.getValue() + "%");
                    }
                    case "between" -> {
                        List<Object> values = p.getValues();
                        if (values == null || values.size() < 2) {
                            continue;
                        }
                        if (!DfValues.isEmpty(values.get(0))) {
                            conditions.add(cmp + " >= ?");
                            params.add(searchValue(values.get(0), type));
                        }
                        if (!DfValues.isEmpty(values.get(1))) {
                            conditions.add(cmp + " <= ?");
                            params.add(searchValue(values.get(1), type));
                        }
                    }
                    case "in", "not in", "not_in" -> {
                        List<Object> values = p.getValues();
                        if (CollectionUtils.isEmpty(values)) {
                            if (DfValues.isEmpty(p.getValue())) {
                                continue;
                            }
                            values = List.of(p.getValue());
                        }
                        boolean not = !term.equals("in");
                        if (p.isMultiple()) {
                            List<String> ors = new ArrayList<>();
                            for (Object v : values) {
                                ors.add(col + " LIKE ?");
                                params.add("%" + DfJson.write(String.valueOf(v)) + "%");
                            }
                            conditions.add((not ? "NOT " : "") + "(" + String.join(" OR ", ors) + ")");
                        } else {
                            List<String> marks = new ArrayList<>();
                            for (Object v : values) {
                                marks.add("?");
                                params.add(searchValue(v, type));
                            }
                            conditions.add(cmp + (not ? " NOT IN (" : " IN (") + String.join(", ", marks) + ")");
                        }
                    }
                    default -> {
                        if (DfValues.isEmpty(p.getValue())) {
                            continue;
                        }
                        String op = switch (term) {
                            case "not_eq" -> "<>";
                            case "lt" -> "<";
                            case "gt" -> ">";
                            case "le" -> "<=";
                            case "ge" -> ">=";
                            default -> "=";
                        };
                        conditions.add(cmp + " " + op + " ?");
                        params.add(searchValue(p.getValue(), type));
                    }
                }
            }
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private Object searchValue(Object v, ExtTableField.BaseType type) {
        if (type == ExtTableField.BaseType.datetime || type == ExtTableField.BaseType.number || type == ExtTableField.BaseType.decimal) {
            return DfValues.toDb(v, type, "");
        }
        return v == null ? null : String.valueOf(v);
    }

    public Map<String, Object> row(DfSession session, DfFormMeta meta, Object pkValue) {
        DataFillFormTableDataRequest request = new DataFillFormTableDataRequest();
        request.setPrimaryKeyValueList(List.of(String.valueOf(pkValue)));
        request.setWithoutLogs(true);
        Map<String, Integer> types = session.columnTypes(meta.table());
        List<Object> params = new ArrayList<>();
        String where = where(session, meta, types, null, request.getPrimaryKeyValueList(), params);
        List<String> select = new ArrayList<>();
        select.add(session.q(meta.pk()));
        for (DfFormMeta.Column c : meta.columns()) {
            if (types.containsKey(c.name().toLowerCase())) {
                select.add(session.q(c.name()));
            }
        }
        List<Map<String, Object>> rows = normalize(meta, session.query("SELECT " + String.join(", ", select) + " FROM "
                + session.table(meta.table()) + where, params));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ------------------------------------------------------------------ write

    /**
     * 写入选项
     */
    public static class SaveOptions {
        /**
         * 不允许修改的列（模板中禁用的字段）
         */
        public Set<String> disabledColumns = new HashSet<>();

        public List<ExtFormSettings> templateSettings = new ArrayList<>();

        public Long userTaskId;

        /**
         * 只允许更新，不允许插入
         */
        public boolean updateOnly;

        /**
         * 只允许插入
         */
        public boolean insertOnly;

        /**
         * 更新时限定的主键值
         */
        public String expectedPk;
    }

    public DataFillFormTableDataResponse saveRow(Long formId, Map<String, Object> data, SaveOptions options) {
        DfFormMeta meta = meta(formId);
        DataFillFormTableDataResponse response = new DataFillFormTableDataResponse();
        response.setKey(meta.pk());
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
            Map<String, Integer> types = session.columnTypes(meta.table());
            Object rawPk = data == null ? null : findPk(meta, data);
            if (options.insertOnly) {
                rawPk = null;
            }
            if (options.expectedPk != null) {
                if (rawPk != null && !StringUtils.equals(String.valueOf(rawPk), options.expectedPk)) {
                    access.deny();
                }
                rawPk = options.expectedPk;
            }
            boolean insert = DfValues.isEmpty(rawPk);
            if (insert && options.updateOnly) {
                access.deny();
            }
            Map<String, Object> current = insert ? null : row(session, meta, rawPk);
            if (!insert && current == null) {
                DEException.throwException(Translator.get("ee_df_data_not_exists"));
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            Map<String, Object> merged = current == null ? new HashMap<>() : new HashMap<>(current);
            if (data != null) {
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    DfFormMeta.Column c = meta.column(e.getKey());
                    if (c == null || !types.containsKey(c.name().toLowerCase())) {
                        continue;
                    }
                    if (!insert && options.disabledColumns.contains(c.name().toLowerCase())) {
                        continue;
                    }
                    Object v = e.getValue();
                    if (c.multiple()) {
                        v = DfValues.toJsonArray(v);
                    }
                    Object dbValue = DfValues.toDb(v, c.type(), c.label());
                    values.put(c.name(), dbValue);
                    merged.put(c.name(), dbValue);
                }
            }
            validate(session, meta, values, merged, insert, insert ? null : rawPk, types, options.templateSettings);
            String pkName = meta.pk();
            Object pkValue;
            if (insert) {
                List<TableFieldWithValue> fields = new ArrayList<>();
                List<Object> params = new ArrayList<>();
                values.forEach((k, v) -> {
                    fields.add(new TableFieldWithValue().setFiledName(k).setValue(v));
                    params.add(v);
                });
                if (fields.isEmpty()) {
                    DEException.throwException(Translator.get("ee_df_data_empty"));
                }
                List<String> keys = session.insert(ddl.insertDataSql(meta.table(), fields, 1), params, pkName);
                pkValue = keys.isEmpty() ? null : keys.getFirst();
                if (pkValue == null) {
                    DEException.throwException(Translator.get("ee_df_data_key_missing"));
                }
            } else {
                pkValue = rawPk;
                if (!values.isEmpty()) {
                    List<TableFieldWithValue> fields = new ArrayList<>();
                    List<Object> params = new ArrayList<>();
                    values.forEach((k, v) -> {
                        fields.add(new TableFieldWithValue().setFiledName(k).setValue(v));
                        params.add(v);
                    });
                    params.add(DfValues.pkValue(pkValue, types.get(pkName.toLowerCase())));
                    session.update(ddl.updateDataByIdSql(meta.table(), fields, new TableFieldWithValue().setFiledName(pkName)), params);
                }
            }
            writeLog(formId, String.valueOf(pkValue), insert ? DfConstants.OP_INSERT : DfConstants.OP_UPDATE, null, options.userTaskId);
            Map<String, Object> saved = row(session, meta, pkValue);
            response.setData(saved == null ? Map.of(pkName, String.valueOf(pkValue)) : saved);
        }
        return response;
    }

    private Object findPk(DfFormMeta meta, Map<String, Object> data) {
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (meta.isPk(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 必填、唯一、数值规则校验
     */
    private void validate(DfSession session, DfFormMeta meta, Map<String, Object> values, Map<String, Object> merged, boolean insert,
                          Object pk, Map<String, Integer> types, List<ExtFormSettings> templateSettings) {
        for (ExtTableField f : meta.fields()) {
            ExtTableField.ExtTableFieldSetting s = f.getSettings();
            if (s == null) {
                continue;
            }
            for (DfFormMeta.Column c : meta.columnsOf(f)) {
                boolean provided = values.containsKey(c.name());
                Object v = values.get(c.name());
                if (s.isRequired() && (insert || provided) && DfValues.isEmpty(v)
                        && !(c.multiple() && "[]".equals(v))) {
                    DEException.throwException(Translator.get("ee_df_value_required", c.label()));
                }
                if (s.isRequired() && c.multiple() && provided && "[]".equals(v)) {
                    DEException.throwException(Translator.get("ee_df_value_required", c.label()));
                }
                if (s.isUnique() && provided && !DfValues.isEmpty(v)) {
                    List<Object> params = new ArrayList<>();
                    params.add(v);
                    String sql = "SELECT COUNT(1) FROM " + session.table(meta.table()) + " WHERE "
                            + session.dialect().comparable(session.q(c.name()), types.get(c.name().toLowerCase())) + " = ?";
                    if (pk != null) {
                        sql += " AND " + session.q(meta.pk()) + " <> ?";
                        params.add(DfValues.pkValue(pk, types.get(meta.pk().toLowerCase())));
                    }
                    if (session.count(sql, params) > 0) {
                        DEException.throwException(Translator.get("ee_df_value_duplicate", c.label(), String.valueOf(v)));
                    }
                }
            }
        }
        if (CollectionUtils.isEmpty(templateSettings)) {
            return;
        }
        for (ExtFormSettings setting : templateSettings) {
            if (CollectionUtils.isEmpty(setting.getNumberInputRules())) {
                continue;
            }
            ExtTableField f = meta.field(setting.getId());
            if (f == null) {
                continue;
            }
            List<DfFormMeta.Column> cols = meta.columnsOf(f);
            if (cols.isEmpty()) {
                continue;
            }
            BigDecimal value = decimal(merged.get(cols.getFirst().name()));
            if (value == null) {
                continue;
            }
            for (ExtFormSettings.NumberRule rule : setting.getNumberInputRules()) {
                ExtTableField other = meta.field(rule.getColumn());
                if (other == null || meta.columnsOf(other).isEmpty()) {
                    continue;
                }
                BigDecimal otherValue = decimal(merged.get(meta.columnsOf(other).getFirst().name()));
                if (otherValue == null) {
                    continue;
                }
                int cmp = value.compareTo(otherValue);
                boolean ok = switch (StringUtils.defaultString(rule.getTerm())) {
                    case "lt" -> cmp < 0;
                    case "gt" -> cmp > 0;
                    case "le" -> cmp <= 0;
                    case "ge" -> cmp >= 0;
                    case "eq" -> cmp == 0;
                    default -> true;
                };
                if (!ok) {
                    DEException.throwException(Translator.get("ee_df_number_rule", cols.getFirst().label(),
                            meta.columnsOf(other).getFirst().label()));
                }
            }
        }
    }

    private BigDecimal decimal(Object v) {
        if (DfValues.isEmpty(v)) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteRows(Long formId, List<String> ids, Long userTaskId) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        DfFormMeta meta = meta(formId);
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
            Integer pkType = session.columnTypes(meta.table()).get(meta.pk().toLowerCase());
            int max = ddl.getMaxParams() > 0 ? Math.min(ddl.getMaxParams(), 1000) : 1000;
            for (int i = 0; i < ids.size(); i += max) {
                List<String> chunk = ids.subList(i, Math.min(ids.size(), i + max));
                List<TableFieldWithValue> pks = new ArrayList<>();
                List<Object> params = new ArrayList<>();
                for (String id : chunk) {
                    pks.add(new TableFieldWithValue().setFiledName(meta.pk()).setValue(id));
                    params.add(DfValues.pkValue(id, pkType));
                }
                session.update(ddl.deleteDataByIdsSql(meta.table(), pks), params);
            }
        }
        for (String id : ids) {
            writeLog(formId, id, DfConstants.OP_DELETE, null, userTaskId);
        }
    }

    public void truncate(Long formId) {
        DfFormMeta meta = meta(formId);
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
            session.execute(ddl.truncateTable(meta.table()));
        }
        writeLog(formId, null, DfConstants.OP_DELETE, null, null);
    }

    /**
     * 批量插入（Excel 导入），全部成功或全部回滚，返回新主键
     */
    public List<String> insertRows(DfFormMeta meta, List<Map<String, Object>> rows, Long userTaskId) {
        List<String> keys = new ArrayList<>();
        if (rows.isEmpty()) {
            return keys;
        }
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
            Map<String, Integer> types = session.columnTypes(meta.table());
            Connection connection = session.connection();
            boolean autoCommit = true;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
            } catch (Exception ignore) {
            }
            try {
                int index = 1;
                Map<String, Set<String>> uniqueSeen = new HashMap<>();
                for (Map<String, Object> data : rows) {
                    index++;
                    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                    for (DfFormMeta.Column c : meta.columns()) {
                        if (!types.containsKey(c.name().toLowerCase())) {
                            continue;
                        }
                        Object v = data.get(c.name());
                        if (c.multiple()) {
                            v = DfValues.toJsonArray(v);
                        }
                        values.put(c.name(), DfValues.toDb(v, c.type(), c.label()));
                    }
                    try {
                        validate(session, meta, values, values, true, null, types, null);
                        for (DfFormMeta.Column c : meta.columns()) {
                            if (c.field().getSettings() != null && c.field().getSettings().isUnique() && !DfValues.isEmpty(values.get(c.name()))) {
                                if (!uniqueSeen.computeIfAbsent(c.name(), k -> new HashSet<>()).add(String.valueOf(values.get(c.name())))) {
                                    DEException.throwException(Translator.get("ee_df_value_duplicate", c.label(), String.valueOf(values.get(c.name()))));
                                }
                            }
                        }
                    } catch (DEException e) {
                        DEException.throwException(Translator.get("ee_df_excel_row_error", index, e.getMessage()));
                    }
                    List<TableFieldWithValue> fields = new ArrayList<>();
                    List<Object> params = new ArrayList<>();
                    values.forEach((k, v) -> {
                        fields.add(new TableFieldWithValue().setFiledName(k).setValue(v));
                        params.add(v);
                    });
                    List<String> generated = session.insert(ddl.insertDataSql(meta.table(), fields, 1), params, meta.pk());
                    if (!generated.isEmpty()) {
                        keys.add(generated.getFirst());
                    }
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception ignore) {
                }
                if (e instanceof DEException de) {
                    throw de;
                }
                DEException.throwException(e.getMessage());
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (Exception ignore) {
                }
            }
        }
        DfCommitRecord record = newLog(meta.form().getId(), null, DfConstants.OP_IMPORT, rows.size(), userTaskId);
        logRepository.save(record);
        return keys;
    }

    // ------------------------------------------------------------------ options

    public List<ColumnOption> columnOptions(Long formId, String fieldId) {
        DfFormMeta meta = meta(formId);
        access.requireReadOrFiller(formId);
        ExtTableField f = meta.field(fieldId);
        if (f == null || f.getSettings() == null) {
            return new ArrayList<>();
        }
        ExtTableField.ExtTableFieldSetting s = f.getSettings();
        if (!Objects.equals(s.getOptionSourceType(), 2)) {
            List<ColumnOption> list = new ArrayList<>();
            if (s.getOptions() != null) {
                s.getOptions().forEach(o -> list.add(new ColumnOption(o.getName(), o.getValue())));
            }
            return list;
        }
        return options(s.getOptionDatasource(), s.getOptionTable(), s.getOptionColumn(), s.getOptionOrder(), s.getPermissionsTree(), false);
    }

    public List<ColumnOption> optionsPreview(Long dsId, DatasourceOptionsRequest request) {
        return options(dsId, request.getOptionTable(), request.getOptionColumn(), request.getOptionOrder(), request.getPermissionsTree(), true);
    }

    private void checkDatasourceRead(Long dsId) {
        if (dsId == null || dsId == DfConstants.ENGINE_DS_ID || V3UserUtil.isSysAdmin()) {
            return;
        }
        if (!permissionManage.effective(V3UserUtil.getUid(), V3UserUtil.getOid(), EeConstants.RT_DATASOURCE, dsId)
                .satisfies(EeConstants.WEIGHT_READ)) {
            access.deny();
        }
    }

    private List<ColumnOption> options(Long dsId, String table, String column, String order, ExtTableField.DfRowPermissionsTreeObj tree,
                                       boolean checkPerm) {
        List<ColumnOption> result = new ArrayList<>();
        if (dsId == null || StringUtils.isAnyBlank(table, column)) {
            return result;
        }
        if (checkPerm) {
            checkDatasourceRead(dsId);
        }
        try (DfSession session = jdbcSupport.open(dsId)) {
            List<Object> params = new ArrayList<>();
            Map<String, Integer> types = session.columnTypes(table);
            String where = treeWhere(session, types, tree, params);
            String col = session.dialect().comparable(session.q(column), types.get(column.toLowerCase()));
            String sql = "SELECT DISTINCT " + col + " FROM " + session.table(table) + " WHERE " + session.q(column) + " IS NOT NULL"
                    + (StringUtils.isBlank(where) ? "" : " AND (" + where + ")")
                    + " ORDER BY " + col + (StringUtils.equalsIgnoreCase(order, "desc") ? " DESC" : " ASC");
            for (List<String> row : session.queryStrings(sql, params, OPTION_LIMIT)) {
                String v = row.getFirst();
                if (v != null) {
                    result.add(new ColumnOption(v, v));
                }
            }
        }
        return result;
    }

    public List<ExtraDetails> extraDetails(ExtraDetailsBaseRequest request) {
        DfFormMeta meta = meta(request.getFormId());
        access.requireReadOrFiller(request.getFormId());
        ExtTableField f = meta.field(request.getColumnId());
        if (f == null || f.getSettings() == null) {
            return new ArrayList<>();
        }
        ExtTableField.ExtTableFieldSetting s = f.getSettings();
        return extra(s.getOptionDatasource(), s.getOptionTable(), s.getOptionColumn(), s.getExtraColumns(), request.getValue(),
                s.getPermissionsTree(), false);
    }

    public List<ExtraDetails> extraDetailsPreview(ExtraDetailsRequest request) {
        return extra(request.getOptionDatasource(), request.getOptionTable(), request.getOptionColumn(), request.getExtraColumns(),
                request.getValue(), request.getPermissionsTree(), true);
    }

    private List<ExtraDetails> extra(Long dsId, String table, String column, List<ExtraColumnItem> extraColumns, String value,
                                     ExtTableField.DfRowPermissionsTreeObj tree, boolean checkPerm) {
        List<ExtraDetails> result = new ArrayList<>();
        if (dsId == null || StringUtils.isAnyBlank(table, column) || CollectionUtils.isEmpty(extraColumns) || value == null) {
            return result;
        }
        if (checkPerm) {
            checkDatasourceRead(dsId);
        }
        try (DfSession session = jdbcSupport.open(dsId)) {
            List<String> select = new ArrayList<>();
            List<ExtraColumnItem> valid = extraColumns.stream().filter(e -> StringUtils.isNotBlank(e.getFieldName())).toList();
            if (valid.isEmpty()) {
                return result;
            }
            valid.forEach(e -> select.add(session.q(e.getFieldName())));
            List<Object> params = new ArrayList<>();
            params.add(value);
            Map<String, Integer> types = session.columnTypes(table);
            String where = treeWhere(session, types, tree, params);
            String sql = "SELECT " + String.join(", ", select) + " FROM " + session.table(table) + " WHERE "
                    + session.dialect().comparable(session.q(column), types.get(column.toLowerCase())) + " = ?"
                    + (StringUtils.isBlank(where) ? "" : " AND (" + where + ")");
            List<List<String>> rows = session.queryStrings(sql, params, 1);
            List<String> row = rows.isEmpty() ? null : rows.getFirst();
            for (int i = 0; i < valid.size(); i++) {
                ExtraColumnItem item = valid.get(i);
                result.add(new ExtraDetails(StringUtils.defaultIfBlank(item.getDisplayName(), item.getFieldName()), row == null ? null : row.get(i)));
            }
        }
        return result;
    }

    /**
     * 选项数据源的行过滤树
     */
    private String treeWhere(DfSession session, Map<String, Integer> types, ExtTableField.DfRowPermissionsTreeObj tree, List<Object> params) {
        if (tree == null || CollectionUtils.isEmpty(tree.getItems())) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (ExtTableField.DfPermissionsTreeItem item : tree.getItems()) {
            if (item == null) {
                continue;
            }
            if (StringUtils.equals(item.getType(), "tree")) {
                String sub = treeWhere(session, types, item.getSubTree(), params);
                if (StringUtils.isNotBlank(sub)) {
                    parts.add("(" + sub + ")");
                }
                continue;
            }
            if (StringUtils.isBlank(item.getFieldId())) {
                continue;
            }
            String raw = session.q(item.getFieldId());
            Integer jdbcType = types.get(item.getFieldId().toLowerCase());
            String col = session.dialect().comparable(raw, jdbcType);
            String term = StringUtils.defaultIfBlank(item.getTerm(), "eq");
            if (StringUtils.equals(item.getFilterType(), "enum")) {
                List<String> values = item.getEnumValue();
                if (CollectionUtils.isEmpty(values)) {
                    continue;
                }
                List<String> marks = new ArrayList<>();
                values.forEach(v -> {
                    marks.add("?");
                    params.add(v);
                });
                parts.add(col + (StringUtils.equals(term, "not in") ? " NOT IN (" : " IN (") + String.join(", ", marks) + ")");
                continue;
            }
            String value = item.getValue();
            switch (term) {
                case "null" -> parts.add(raw + " IS NULL");
                case "not_null" -> parts.add(raw + " IS NOT NULL");
                case "empty" -> parts.add(session.dialect().emptyCondition(raw, jdbcType));
                case "not_empty" -> parts.add(session.dialect().notEmptyCondition(raw, jdbcType));
                case "like" -> {
                    parts.add(raw + " LIKE ?");
                    params.add("%" + StringUtils.defaultString(value) + "%");
                }
                case "not like" -> {
                    parts.add(raw + " NOT LIKE ?");
                    params.add("%" + StringUtils.defaultString(value) + "%");
                }
                case "in", "not in" -> {
                    List<String> values = value == null ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
                    if (values.isEmpty()) {
                        continue;
                    }
                    List<String> marks = new ArrayList<>();
                    values.forEach(v -> {
                        marks.add("?");
                        params.add(v);
                    });
                    parts.add(col + (term.equals("in") ? " IN (" : " NOT IN (") + String.join(", ", marks) + ")");
                }
                case "between" -> {
                    String[] range = StringUtils.defaultString(value).split(",");
                    if (range.length < 2) {
                        continue;
                    }
                    parts.add(col + " BETWEEN ? AND ?");
                    params.add(range[0].trim());
                    params.add(range[1].trim());
                }
                default -> {
                    String op = switch (term) {
                        case "not_eq" -> "<>";
                        case "lt" -> "<";
                        case "le" -> "<=";
                        case "gt" -> ">";
                        case "ge" -> ">=";
                        default -> "=";
                    };
                    parts.add(col + " " + op + " ?");
                    params.add(value);
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(StringUtils.equalsIgnoreCase(tree.getLogic(), "or") ? " OR " : " AND ", parts);
    }

    // ------------------------------------------------------------------ logs

    private DfCommitRecord newLog(Long formId, String dataId, int operate, Integer count, Long userTaskId) {
        DfCommitRecord record = new DfCommitRecord();
        record.setId(IDUtils.snowID());
        record.setFormId(formId);
        record.setDataId(dataId);
        record.setOperate(operate);
        record.setCount(count);
        record.setCommitBy(V3UserUtil.getUid());
        record.setCommitTime(System.currentTimeMillis());
        record.setUserTaskId(userTaskId);
        return record;
    }

    public void writeLog(Long formId, String dataId, int operate, Integer count, Long userTaskId) {
        logRepository.save(newLog(formId, dataId, operate, count, userTaskId));
    }

    private Map<String, DfCommitLog> latestLogs(Long formId, List<String> dataIds) {
        Map<String, DfCommitLog> result = new HashMap<>();
        if (dataIds.isEmpty()) {
            return result;
        }
        QDfCommitRecord q = QDfCommitRecord.dfCommitRecord;
        List<DfCommitRecord> list = queryFactory.selectFrom(q)
                .where(q.formId.eq(formId), q.dataId.in(dataIds), q.operate.in(DfConstants.OP_INSERT, DfConstants.OP_UPDATE))
                .orderBy(q.commitTime.desc()).fetch();
        Map<Long, String> names = users.names(list.stream().map(DfCommitRecord::getCommitBy).toList());
        for (DfCommitRecord r : list) {
            result.putIfAbsent(r.getDataId(), toLog(r, names));
        }
        return result;
    }

    private DfCommitLog toLog(DfCommitRecord r, Map<Long, String> names) {
        return new DfCommitLog(r.getId(), r.getFormId(), r.getDataId(), r.getOperate(), r.getCommitBy(), r.getCommitTime(),
                r.getCount(), names.get(r.getCommitBy()));
    }

    public PageResult<DfCommitLog> logPage(DfCommitLogRequest request, int goPage, int pageSize) {
        access.form(request.getFormId());
        QDfCommitRecord q = QDfCommitRecord.dfCommitRecord;
        BooleanExpression where = q.formId.eq(request.getFormId());
        if (request.getOperate() != null && request.getOperate() >= 0) {
            where = where.and(q.operate.eq(request.getOperate()));
        }
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        Long total = queryFactory.select(q.id.count()).from(q).where(where).fetchOne();
        List<DfCommitRecord> list = queryFactory.selectFrom(q).where(where).orderBy(q.commitTime.desc(), q.id.desc())
                .offset((long) (page - 1) * size).limit(size).fetch();
        Map<Long, String> names = users.names(list.stream().map(DfCommitRecord::getCommitBy).toList());
        return new PageResult<>(list.stream().map(r -> toLog(r, names)).toList(), ObjectUtils.defaultIfNull(total, 0L),
                PageRequest.of(page - 1, size));
    }

    @Transactional
    public void clearLog(DfClearCommitLogRequest request) {
        access.form(request.getFormId());
        long day = 24L * 3600L * 1000L;
        long range = switch (StringUtils.defaultString(request.getClearType(), "1")) {
            case "2" -> 7 * day;
            case "3" -> 30 * day;
            case "4" -> 90 * day;
            case "5" -> 180 * day;
            case "6" -> 365 * day;
            default -> day;
        };
        QDfCommitRecord q = QDfCommitRecord.dfCommitRecord;
        queryFactory.delete(q).where(q.formId.eq(request.getFormId()), q.commitTime.lt(System.currentTimeMillis() - range)).execute();
    }

    @Transactional
    public void deleteLogsByForm(Long formId) {
        QDfCommitRecord q = QDfCommitRecord.dfCommitRecord;
        queryFactory.delete(q).where(q.formId.eq(formId)).execute();
    }

    /**
     * 按“数据填报日志保留时间”（天）清理提交日志
     */
    @Scheduled(cron = "0 17 2 * * ?")
    @Transactional
    public void cleanExpiredLogs() {
        try {
            String val = sysParameterManage.singleVal(XpackSettingConstants.DATA_FILLING_LOG_LIVE_TIME);
            if (StringUtils.isBlank(val) || !StringUtils.isNumeric(val.trim())) {
                return;
            }
            long days = Long.parseLong(val.trim());
            if (days <= 0) {
                return;
            }
            QDfCommitRecord q = QDfCommitRecord.dfCommitRecord;
            long removed = queryFactory.delete(q).where(q.commitTime.lt(System.currentTimeMillis() - days * 24L * 3600L * 1000L)).execute();
            LogUtil.info("data filling commit logs cleaned: " + removed);
        } catch (Exception e) {
            LogUtil.error("clean data filling logs failed: " + e.getMessage(), e);
        }
    }
}
