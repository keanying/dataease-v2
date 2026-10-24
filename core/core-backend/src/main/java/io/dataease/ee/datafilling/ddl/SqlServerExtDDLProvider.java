package io.dataease.ee.datafilling.ddl;

import io.dataease.ee.datafilling.DfConstants;
import io.dataease.extensions.datafilling.dto.ExtIndexField;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 的数据填报 DDL/DML 生成器（多条语句以分号分隔）。
 * <p>Spring Bean 不带模式，仅用于判断是否支持；打开会话时通过 {@link #withSchema(String)} 得到限定模式的实例，
 * 表名统一生成为 {@code [schema].[table]}。</p>
 */
@Component("sqlServerExtDDLProvider")
public class SqlServerExtDDLProvider extends PgExtDDLProvider {

    private final String schema;

    public SqlServerExtDDLProvider() {
        this.schema = null;
    }

    private SqlServerExtDDLProvider(String schema) {
        this.schema = StringUtils.trimToNull(schema);
    }

    /**
     * 限定模式的实例
     */
    public ExtDDLProvider withSchema(String schema) {
        return new SqlServerExtDDLProvider(schema);
    }

    @Override
    protected String quote(String name) {
        return "[" + StringUtils.replace(name, "]", "]]") + "]";
    }

    @Override
    protected String table(String name) {
        return schema == null ? quote(name) : quote(schema) + "." + quote(name);
    }

    @Override
    protected String literal(String text) {
        return "N'" + StringUtils.replace(StringUtils.defaultString(text), "'", "''") + "'";
    }

    @Override
    public String createTableSql(String table, List<ExtTableField> formFields) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table(table)).append(" (");
        sb.append(quote(DfConstants.DEFAULT_PK)).append(" BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY");
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            sb.append(", ").append(quote(column.getColumnName())).append(" ").append(columnType(column)).append(" NULL");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 表、列注释写入扩展属性 MS_Description；每个元素为一条独立语句
     */
    @Override
    public List<String> createComment(String table, List<ExtTableField> formFields, String tableComment) {
        List<String> result = new ArrayList<>();
        String level0 = schema == null ? "N'dbo'" : literal(schema);
        if (StringUtils.isNotBlank(tableComment)) {
            result.add("EXEC sp_addextendedproperty @name = N'MS_Description', @value = " + literal(StringUtils.left(tableComment, 3000))
                    + ", @level0type = N'SCHEMA', @level0name = " + level0 + ", @level1type = N'TABLE', @level1name = " + literal(table));
        }
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            if (StringUtils.isNotBlank(column.getComment())) {
                result.add("EXEC sp_addextendedproperty @name = N'MS_Description', @value = " + literal(StringUtils.left(column.getComment(), 3000))
                        + ", @level0type = N'SCHEMA', @level0name = " + level0 + ", @level1type = N'TABLE', @level1name = " + literal(table)
                        + ", @level2type = N'COLUMN', @level2name = " + literal(column.getColumnName()));
            }
        }
        return result;
    }

    @Override
    protected String columnType(ExtTableField.TableField column) {
        ExtTableField.BaseType type = column.getType() == null ? ExtTableField.BaseType.nvarchar : column.getType();
        return switch (type) {
            case text -> "NVARCHAR(MAX)";
            case number -> "BIGINT";
            case decimal -> "DECIMAL(" + precision(column.getSize(), 20) + "," + scale(column.getSize(), column.getAccuracy()) + ")";
            case datetime -> "DATETIME2";
            default -> {
                int size = column.getSize() == null ? 255 : column.getSize();
                yield size > 4000 || size <= 0 ? "NVARCHAR(MAX)" : "NVARCHAR(" + size + ")";
            }
        };
    }

    static int precision(Integer size, int def) {
        int p = size == null ? def : size;
        return Math.max(1, Math.min(p, 38));
    }

    static int scale(Integer size, Integer accuracy) {
        int s = accuracy == null ? 8 : accuracy;
        return Math.max(0, Math.min(s, precision(size, 20)));
    }

    @Override
    public String addTableColumnSql(String table, List<ExtTableField> formFieldsToCreate, List<ExtTableField> formFieldsToModify) {
        List<String> statements = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToModify)) {
            if (StringUtils.isNotBlank(column.getOldColumnName()) && !StringUtils.equals(column.getOldColumnName(), column.getColumnName())) {
                String path = (schema == null ? "" : quote(schema) + ".") + quote(table) + "." + quote(column.getOldColumnName());
                statements.add("EXEC sp_rename " + literal(path) + ", " + literal(column.getColumnName()) + ", N'COLUMN'");
            }
        }
        List<String> adds = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFieldsToCreate)) {
            adds.add(quote(column.getColumnName()) + " " + columnType(column) + " NULL");
        }
        if (!adds.isEmpty()) {
            statements.add("ALTER TABLE " + table(table) + " ADD " + String.join(", ", adds));
        }
        return statements.isEmpty() ? null : String.join(";", statements);
    }

    @Override
    public String dropTableColumnSql(String table, List<ExtTableField> formFields) {
        List<String> parts = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            parts.add(quote(column.getColumnName()));
        }
        return parts.isEmpty() ? null : "ALTER TABLE " + table(table) + " DROP COLUMN " + String.join(", ", parts);
    }

    @Override
    public String dropTableSql(String table) {
        return "DROP TABLE " + table(table);
    }

    @Override
    public List<String> dropTableIndexSql(String table, List<ExtIndexField> indexFields) {
        List<String> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(indexFields)) {
            return result;
        }
        for (ExtIndexField index : indexFields) {
            result.add("DROP INDEX " + quote(index.getName()) + " ON " + table(table));
        }
        return result;
    }

    @Override
    public Integer getColumnType(String name) {
        if (name == null) {
            return Types.NVARCHAR;
        }
        return switch (name.toLowerCase()) {
            case "text" -> Types.LONGNVARCHAR;
            case "number" -> Types.BIGINT;
            case "decimal" -> Types.DECIMAL;
            case "datetime" -> Types.TIMESTAMP;
            default -> Types.NVARCHAR;
        };
    }

    /**
     * SQL Server 单条语句最多 2100 个参数
     */
    @Override
    public int getMaxParams() {
        return 2000;
    }
}
