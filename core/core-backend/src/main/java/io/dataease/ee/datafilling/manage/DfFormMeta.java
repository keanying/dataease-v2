package io.dataease.ee.datafilling.manage;

import io.dataease.ee.datafilling.DfConstants;
import io.dataease.ee.datafilling.dao.entity.DfForm;
import io.dataease.ee.datafilling.ddl.DfDDLSupport;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 表单与数据表列的对应关系
 */
public class DfFormMeta {

    /**
     * 一个数据表列
     */
    public record Column(String name, ExtTableField field, ExtTableField.BaseType type, int rangeIndex) {

        public String label() {
            return field.getSettings() == null ? name : StringUtils.defaultIfBlank(field.getSettings().getName(), name);
        }

        public boolean multiple() {
            return StringUtils.equals(field.getType(), "checkbox")
                    || (StringUtils.equals(field.getType(), "select") && field.getSettings() != null && field.getSettings().isMultiple());
        }

        public boolean date() {
            return StringUtils.equalsAny(field.getType(), "date", "dateRange");
        }

        public String dateType() {
            return field.getSettings() == null ? null : field.getSettings().getDateType();
        }
    }

    private final DfForm form;

    private final List<ExtTableField> fields;

    private final List<Column> columns = new ArrayList<>();

    private final Map<String, Column> byName = new HashMap<>();

    private final Map<String, ExtTableField> byId = new HashMap<>();

    public DfFormMeta(DfForm form) {
        this.form = form;
        this.fields = DfJson.activeFields(form.getForms());
        for (ExtTableField f : fields) {
            if (f.getId() != null) {
                byId.put(f.getId(), f);
            }
            ExtTableField.ExtTableFieldMapping m = f.getSettings() == null ? null : f.getSettings().getMapping();
            if (m == null) {
                continue;
            }
            ExtTableField.BaseType type = m.getType() == null ? DfDDLSupport.inferType(f) : m.getType();
            if (StringUtils.equals(f.getType(), "dateRange")) {
                add(new Column(m.getColumnName1(), f, type, 0));
                add(new Column(m.getColumnName2(), f, type, 1));
            } else {
                add(new Column(m.getColumnName(), f, type, -1));
            }
        }
    }

    private void add(Column column) {
        if (StringUtils.isBlank(column.name())) {
            return;
        }
        columns.add(column);
        byName.put(column.name().toLowerCase(), column);
    }

    public DfForm form() {
        return form;
    }

    public String table() {
        return form.getTableName();
    }

    public String pk() {
        return StringUtils.defaultIfBlank(form.getPrimaryKey(), DfConstants.DEFAULT_PK);
    }

    public List<ExtTableField> fields() {
        return fields;
    }

    public List<Column> columns() {
        return columns;
    }

    public Column column(String name) {
        return name == null ? null : byName.get(name.toLowerCase());
    }

    public ExtTableField field(String id) {
        return id == null ? null : byId.get(id);
    }

    public boolean isPk(String name) {
        return StringUtils.equalsIgnoreCase(name, pk());
    }

    /**
     * 字段对应的列（日期范围为两列）
     */
    public List<Column> columnsOf(ExtTableField field) {
        return columns.stream().filter(c -> c.field() == field).toList();
    }
}
