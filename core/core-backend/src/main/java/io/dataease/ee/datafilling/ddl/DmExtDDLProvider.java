package io.dataease.ee.datafilling.ddl;

import io.dataease.extensions.datafilling.dto.ExtTableField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 达梦（DM8）的数据填报 DDL/DML 生成器：语法与 Oracle 基本一致，主键使用 IDENTITY，逐列增加字段。
 * <p>本地无达梦环境，未经运行验证。</p>
 */
@Component("dmExtDDLProvider")
public class DmExtDDLProvider extends OracleExtDDLProvider {

    @Override
    protected String identityColumn() {
        return "BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY";
    }

    @Override
    protected String columnType(ExtTableField.TableField column) {
        ExtTableField.BaseType type = column.getType() == null ? ExtTableField.BaseType.nvarchar : column.getType();
        if (type == ExtTableField.BaseType.number) {
            return "BIGINT";
        }
        return super.columnType(column);
    }

    @Override
    protected String varchar(int size) {
        return size > 8188 || size <= 0 ? "CLOB" : "VARCHAR(" + size + " CHAR)";
    }

    @Override
    protected List<String> addColumns(String table, List<String> definitions) {
        List<String> result = new ArrayList<>();
        for (String definition : definitions) {
            result.add("ALTER TABLE " + table(table) + " ADD COLUMN " + definition);
        }
        return result;
    }

    @Override
    public String dropTableColumnSql(String table, List<ExtTableField> formFields) {
        List<String> parts = new ArrayList<>();
        for (ExtTableField.TableField column : DfDDLSupport.columns(formFields)) {
            parts.add("ALTER TABLE " + table(table) + " DROP COLUMN " + quote(column.getColumnName()));
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }
}
