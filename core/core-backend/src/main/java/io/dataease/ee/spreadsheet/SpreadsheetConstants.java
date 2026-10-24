package io.dataease.ee.spreadsheet;

import java.util.Set;

/**
 * 电子表格模块常量
 */
public final class SpreadsheetConstants {

    private SpreadsheetConstants() {
    }

    public static final String NODE_FOLDER = "folder";

    public static final String NODE_SHEET = "sheet";

    /**
     * 发布状态
     */
    public static final int STATUS_UNPUBLISHED = 0;

    public static final int STATUS_PUBLISHED = 1;

    public static final int STATUS_SAVED_UNPUBLISHED = 2;

    /**
     * 系统参数：电子表格单次查询数据上限
     */
    public static final String QUERY_LIMIT_KEY = "basic.spreadsheetQueryLimit";

    public static final long DEFAULT_QUERY_LIMIT = 100000L;

    /**
     * 插件未设置时的默认结果数量
     */
    public static final long DEFAULT_RESULT_LIMIT = 1000L;

    public static final String TYPE_DETAIL = "detail";

    public static final String TYPE_PIVOT = "pivot";

    /**
     * 工作簿 resources 中保存插件实例的资源名
     */
    public static final Set<String> PLUGIN_RESOURCE_NAMES = Set.of(
            "SHEET_DATAEASE_DETAIL_TABLE_PLUGIN",
            "SHEET_DATAEASE_PIVOT_TABLE_PLUGIN",
            "SHEET_DATAEASE_FILTER_PLUGIN",
            "SHEET_DATAEASE_SLASH_CELL_PLUGIN");

    /**
     * 插件配置中引用数据集的属性名
     */
    public static final Set<String> DATASET_KEYS = Set.of("datasetId", "optionDatasetId", "treeDatasetId");

    public static final int NAME_MAX_LENGTH = 64;
}
