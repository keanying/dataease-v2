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
 * MySQL / MariaDB 的数据填报 DDL/DML 生成器。
 * <p>Bean 名称 {@code mysqlExtDDLProvider} 为 SDK {@code ExtDDLProviderFactory} 的约定。</p>
 */
@Component("mysqlExtDDLProvider")
public class MysqlExtDDLProvider extends ExtDDLProvider {

    protected String quote(String name) {
        return "`" + StringUtils.replace(name, "`", "``") + "`";
    }

    protected String literal(String text) {
        return "'" + StringUtils.replace(StringUtils.replace(StringUtils.defaultString(text), "\\", "\\\\"), "'", "''") + "'";
    }

    @Override
    public boolean useCreateSqlWithComment() {
        return true;
    }

    @Override
    public String createTableSql(String table, List<ExtTableField> formFields) throws Exception {
        return createTableSqlWithComment(table, formFields, null);
    }

    @Override
    public String createTableSqlWithComment(String table, List<ExtTableField> formFields, String tableComment) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quote(table)).append(" (");
        sb.append(quote(DfConstants.DEFAULT_PK)).append(" BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID'");
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            sb.append(", ").append(columnDefinition(column));
        }
        sb.append(", PRIMARY KEY (").append(quote(DfConstants.DEFAULT_PK)).append(")");
        sb.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        if (StringUtils.isNotBlank(tableComment)) {
            sb.append(" COMMENT=").append(literal(StringUtils.left(tableComment, 1024)));
        }
        return sb.toString();
    }

    protected String columnDefinition(ExtTableField.TableField column) {
        StringBuilder sb = new StringBuilder(quote(column.getColumnName())).append(" ").append(columnType(column));
        sb.append(column.isRequired() ? " NOT NULL" : " NULL");
        if (StringUtils.isNotBlank(column.getComment())) {
            sb.append(" COMMENT ").append(literal(StringUtils.left(column.getComment(), 1024)));
        }
        return sb.toString();
    }

    protected String columnType(ExtTableField.TableField column) {
        ExtTableField.BaseType type = column.getType() == null ? ExtTableField.BaseType.nvarchar : column.getType();
        return switch (type) {
            case text -> "LONGTEXT";
            case number -> "BIGINT";
            case decimal -> "DECIMAL(" + (column.getSize() == null ? 20 : column.getSize()) + ","
                    + (column.getAccuracy() == null ? 8 : column.getAccuracy()) + ")";
            case datetime -> "DATETIME";
            default -> "VARCHAR(" + (column.getSize() == null ? 255 : column.getSize()) + ")";
        };
    }

    @Override
    public String addTableColumnSql(String table, List<ExtTableField> formFieldsToCreate, List<ExtTableField> formFieldsToModify) {
        List<String> parts = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToModify)) {
            ExtTableField.TableField nullable = ExtTableField.TableField.builder().columnName(column.getColumnName())
                    .type(column.getType()).size(column.getSize()).accuracy(column.getAccuracy()).comment(column.getComment()).build();
            parts.add("CHANGE COLUMN " + quote(StringUtils.defaultIfBlank(column.getOldColumnName(), column.getColumnName())) + " " + columnDefinition(nullable));
        }
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToCreate)) {
            ExtTableField.TableField nullable = ExtTableField.TableField.builder().columnName(column.getColumnName())
                    .type(column.getType()).size(column.getSize()).accuracy(column.getAccuracy()).comment(column.getComment()).build();
            parts.add("ADD COLUMN " + columnDefinition(nullable));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "ALTER TABLE " + quote(table) + " " + String.join(", ", parts);
    }

    @Override
    public String dropTableColumnSql(String table, List<ExtTableField> formFields) {
        List<String> parts = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            parts.add("DROP COLUMN " + quote(column.getColumnName()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "ALTER TABLE " + quote(table) + " " + String.join(", ", parts);
    }

    @Override
    public String dropTableSql(String table) {
        return "DROP TABLE IF EXISTS " + quote(table);
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
            result.add("CREATE INDEX " + quote(index.getName()) + " ON " + quote(table) + " (" + String.join(", ", cols) + ")");
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
            result.add("DROP INDEX " + quote(index.getName()) + " ON " + quote(table));
        }
        return result;
    }

    @Override
    public String deleteDataByIdsSql(String table, List<TableFieldWithValue> pks) {
        String pk = pks.get(0).getFiledName();
        List<String> marks = new ArrayList<>();
        pks.forEach(p -> marks.add("?"));
        return "DELETE FROM " + quote(table) + " WHERE " + quote(pk) + " IN (" + String.join(", ", marks) + ")";
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
        return "INSERT INTO " + quote(tableName) + " (" + String.join(", ", cols) + ") VALUES " + String.join(", ", rows);
    }

    @Override
    public String updateDataByIdSql(String tableName, List<TableFieldWithValue> fields, TableFieldWithValue pk) {
        List<String> sets = new ArrayList<>();
        for (TableFieldWithValue f : fields) {
            sets.add(quote(f.getFiledName()) + " = ?");
        }
        return "UPDATE " + quote(tableName) + " SET " + String.join(", ", sets) + " WHERE " + quote(pk.getFiledName()) + " = ?";
    }

    @Override
    public Integer getColumnType(String name) {
        if (name == null) {
            return Types.VARCHAR;
        }
        return switch (name.toLowerCase()) {
            case "text" -> Types.LONGVARCHAR;
            case "number" -> Types.BIGINT;
            case "decimal" -> Types.DECIMAL;
            case "datetime" -> Types.TIMESTAMP;
            default -> Types.VARCHAR;
        };
    }

    @Override
    public String truncateTable(String table) {
        return "TRUNCATE TABLE " + quote(table);
    }

    @Override
    public String listAllIds(String table, String keyColumn) {
        return "SELECT " + quote(keyColumn) + " FROM " + quote(table);
    }

    @Override
    public int getMaxParams() {
        return 60000;
    }
}
