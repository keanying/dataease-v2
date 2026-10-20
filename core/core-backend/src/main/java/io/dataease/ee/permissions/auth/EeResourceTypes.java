package io.dataease.ee.permissions.auth;

import io.dataease.constant.BusiResourceEnum;
import io.dataease.ee.common.EeConstants;
import org.apache.commons.lang3.StringUtils;

/**
 * 资源类型字符串与资源类型编码的转换
 */
public final class EeResourceTypes {

    private EeResourceTypes() {
    }

    public static Integer rtId(String type) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        return switch (type.trim().toLowerCase()) {
            case "menu" -> EeConstants.RT_MENU;
            case "dashboard", "panel" -> EeConstants.RT_PANEL;
            case "datav", "screen" -> EeConstants.RT_SCREEN;
            case "dataset" -> EeConstants.RT_DATASET;
            case "datasource" -> EeConstants.RT_DATASOURCE;
            case "data_filling", "datafilling", "data-filling" -> EeConstants.RT_DATA_FILLING;
            case "spreadsheet" -> EeConstants.RT_SPREADSHEET;
            default -> null;
        };
    }

    public static Integer rtId(BusiResourceEnum busiEnum) {
        return busiEnum == null ? null : busiEnum.getFlag();
    }

    public static String flag(int rtId) {
        return switch (rtId) {
            case EeConstants.RT_MENU -> "menu";
            case EeConstants.RT_PANEL -> "panel";
            case EeConstants.RT_SCREEN -> "screen";
            case EeConstants.RT_DATASET -> "dataset";
            case EeConstants.RT_DATASOURCE -> "datasource";
            case EeConstants.RT_DATA_FILLING -> "data_filling";
            case EeConstants.RT_SPREADSHEET -> "spreadsheet";
            default -> null;
        };
    }
}
