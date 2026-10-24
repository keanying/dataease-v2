package io.dataease.ee.datafilling.ddl;

import io.dataease.ee.datafilling.DfConstants;
import io.dataease.extensions.datafilling.dto.ExtIndexField;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datasource.dto.TableFieldWithValue;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL / Kingbase 的数据填报 DDL/DML 生成器（多条语句以分号分隔）。
 */
@Component("pgExtDDLProvider")
public class PgExtDDLProvider extends ExtDDLProvider {

    protected String quote(String name) {
        return "\"" + StringUtils.replace(name, "\"", "\"\"") + "\"";
    }

    /**
     * 引用后的表名（子类可加模式前缀）
     */
    protected String table(String name) {
        return quote(name);
    }

    protected String literal(String text) {
        return "'" + StringUtils.replace(StringUtils.defaultString(text), "'", "''") + "'";
    }

    @Override
    public String createTableSql(String table, List<ExtTableField> formFields) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table(table)).append(" (");
        sb.append(quote(DfConstants.DEFAULT_PK)).append(" BIGSERIAL PRIMARY KEY");
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            sb.append(", ").append(quote(column.getColumnName())).append(" ").append(columnType(column)).append(" NULL");
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public List<String> createComment(String table, List<ExtTableField> formFields, String tableComment) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isNotBlank(tableComment)) {
            result.add("COMMENT ON TABLE " + table(table) + " IS " + literal(tableComment));
        }
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            if (StringUtils.isNotBlank(column.getComment())) {
                result.add("COMMENT ON COLUMN " + table(table) + "." + quote(column.getColumnName()) + " IS " + literal(column.getComment()));
            }
        }
        return result;
    }

    protected String columnType(ExtTableField.TableField column) {
        ExtTableField.BaseType type = column.getType() == null ? ExtTableField.BaseType.nvarchar : column.getType();
        return switch (type) {
            case text -> "TEXT";
            case number -> "BIGINT";
            case decimal -> "NUMERIC(" + (column.getSize() == null ? 20 : column.getSize()) + ","
                    + (column.getAccuracy() == null ? 8 : column.getAccuracy()) + ")";
            case datetime -> "TIMESTAMP";
            default -> "VARCHAR(" + (column.getSize() == null ? 255 : column.getSize()) + ")";
        };
    }

    @Override
    public String addTableColumnSql(String table, List<ExtTableField> formFieldsToCreate, List<ExtTableField> formFieldsToModify) {
        List<String> statements = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToModify)) {
            if (StringUtils.isNotBlank(column.getOldColumnName()) && !StringUtils.equals(column.getOldColumnName(), column.getColumnName())) {
                statements.add("ALTER TABLE " + table(table) + " RENAME COLUMN " + quote(column.getOldColumnName()) + " TO " + quote(column.getColumnName()));
            }
        }
        List<String> adds = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToCreate)) {
            adds.add("ADD COLUMN " + quote(column.getColumnName()) + " " + columnType(column) + " NULL");
        }
        if (!adds.isEmpty()) {
            statements.add("ALTER TABLE " + table(table) + " " + String.join(", ", adds));
        }
        return statements.isEmpty() ? null : String.join(";", statements);
    }

    @Override
    public String dropTableColumnSql(String table, List<ExtTableField> formFields) {
        List<String> parts = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            parts.add("DROP COLUMN " + quote(column.getColumnName()));
        }
        return parts.isEmpty() ? null : "ALTER TABLE " + table(table) + " " + String.join(", ", parts);
    }

    @Override
    public String dropTableSql(String table) {
        return "DROP TABLE IF EXISTS " + table(table);
    }

    @Override
    public List<String> createTableIndexSql(String table, List<ExtIndexField> indexFields) {
        List<String> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(indexFields)) {
            return result;
        }
        for (ExtIndexField index : indexFields) {
            if (CollectionUtils.isEmpty(index.getColumns())) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            for (ExtIndexField.ColumnSetting c : index.getColumns()) {
                String order = StringUtils.equalsIgnoreCase(c.getOrder(), "desc") ? " DESC"
                        : StringUtils.equalsIgnoreCase(c.getOrder(), "asc") ? " ASC" : "";
                cols.add(quote(c.getColumn()) + order);
            }
            result.add("CREATE INDEX " + quote(index.getName()) + " ON " + table(table) + " (" + String.join(", ", cols) + ")");
        }
        return result;
    }

    @Override
    public List<String> dropTableIndexSql(String table, List<ExtIndexField> indexFields) {
        List<String> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(indexFields)) {
            return result;
        }
        for (ExtIndexField index : indexFields) {
            result.add("DROP INDEX IF EXISTS " + quote(index.getName()));
        }
        return result;
    }

    @Override
    public String deleteDataByIdsSql(String table, List<TableFieldWithValue> pks) {
        List<String> marks = new ArrayList<>();
        pks.forEach(p -> marks.add("?"));
        return "DELETE FROM " + table(table) + " WHERE " + quote(pks.get(0).getFiledName()) + " IN (" + String.join(", ", marks) + ")";
    }

    @Override
    public String insertDataSql(String tableName, List<TableFieldWithValue> fields, int count) {
        List<String> cols = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (TableFieldWithValue f : fields) {
            cols.add(quote(f.getFiledName()));
            marks.add("?");
        }
        String values = "(" + String.join(", ", marks) + ")";
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Math.max(count, 1); i++) {
            rows.add(values);
        }
        return "INSERT INTO " + table(tableName) + " (" + String.join(", ", cols) + ") VALUES " + String.join(", ", rows);
    }

    @Override
    public String updateDataByIdSql(String tableName, List<TableFieldWithValue> fields, TableFieldWithValue pk) {
        List<String> sets = new ArrayList<>();
        for (TableFieldWithValue f : fields) {
            sets.add(quote(f.getFiledName()) + " = ?");
        }
        return "UPDATE " + table(tableName) + " SET " + String.join(", ", sets) + " WHERE " + quote(pk.getFiledName()) + " = ?";
    }

    @Override
    public Integer getColumnType(String name) {
        if (name == null) {
            return Types.VARCHAR;
        }
        return switch (name.toLowerCase()) {
            case "text" -> Types.LONGVARCHAR;
            case "number" -> Types.BIGINT;
            case "decimal" -> Types.NUMERIC;
            case "datetime" -> Types.TIMESTAMP;
            default -> Types.VARCHAR;
        };
    }

    @Override
    public String truncateTable(String table) {
        return "TRUNCATE TABLE " + table(table);
    }

    @Override
    public String listAllIds(String table, String keyColumn) {
        return "SELECT " + quote(keyColumn) + " FROM " + table(table);
    }

    @Override
    public int getMaxParams() {
        return 30000;
    }
}
