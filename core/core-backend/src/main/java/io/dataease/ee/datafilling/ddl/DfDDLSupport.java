package io.dataease.ee.datafilling.ddl;

import io.dataease.extensions.datafilling.dto.ExtTableField;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 表单项与数据表列之间的转换
 */
public final class DfDDLSupport {

    private DfDDLSupport() {
    }

    /**
     * 表单项展开为数据表列：日期范围对应两列
     */
    public static List<ExtTableField.TableField> columns(List<ExtTableField> fields) {
        List<ExtTableField.TableField> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(fields)) {
            return result;
        }
        for (ExtTableField field : fields) {
            ExtTableField.ExtTableFieldSetting settings = field.getSettings();
            if (settings == null || settings.getMapping() == null) {
                continue;
            }
            ExtTableField.ExtTableFieldMapping mapping = settings.getMapping();
            ExtTableField.BaseType type = mapping.getType() == null ? inferType(field) : mapping.getType();
            if (StringUtils.equals(field.getType(), "dateRange")) {
                result.add(column(mapping.getColumnName1(), mapping.getOldColumnName1(), type, mapping, settings));
                result.add(column(mapping.getColumnName2(), mapping.getOldColumnName2(), type, mapping, settings));
            } else {
                result.add(column(mapping.getColumnName(), mapping.getOldColumnName(), type, mapping, settings));
            }
        }
        result.removeIf(c -> StringUtils.isBlank(c.getColumnName()));
        return result;
    }

    private static ExtTableField.TableField column(String name, String oldName, ExtTableField.BaseType type,
                                                   ExtTableField.ExtTableFieldMapping mapping, ExtTableField.ExtTableFieldSetting settings) {
        return ExtTableField.TableField.builder()
                .columnName(name)
                .oldColumnName(oldName)
                .type(type)
                .size(mapping.getSize())
                .accuracy(mapping.getAccuracy())
                .required(false)
                .comment(settings.getName())
                .build();
    }

    /**
     * 未指定列类型时按组件推断
     */
    public static ExtTableField.BaseType inferType(ExtTableField field) {
        String type = field.getType();
        ExtTableField.ExtTableFieldSetting settings = field.getSettings();
        if (StringUtils.equalsAny(type, "date", "dateRange")) {
            return ExtTableField.BaseType.datetime;
        }
        if (StringUtils.equals(type, "textarea") || StringUtils.equals(type, "checkbox")
                || (StringUtils.equals(type, "select") && settings != null && settings.isMultiple())) {
            return ExtTableField.BaseType.text;
        }
        if (StringUtils.equals(type, "input") && settings != null && StringUtils.equals(settings.getInputType(), "number")) {
            return ExtTableField.BaseType.decimal;
        }
        return ExtTableField.BaseType.nvarchar;
    }
}
