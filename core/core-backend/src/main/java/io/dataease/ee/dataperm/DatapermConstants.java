package io.dataease.ee.dataperm;

import java.util.List;

/**
 * 系统变量与数据集行列权限模块常量
 */
public final class DatapermConstants {

    private DatapermConstants() {
    }

    /**
     * 系统变量类型：文本、数值、时间
     */
    public static final String VAR_TEXT = "text";

    public static final String VAR_NUM = "num";

    public static final String VAR_TIME = "time";

    public static final List<String> VAR_TYPES = List.of(VAR_TEXT, VAR_NUM, VAR_TIME);

    /**
     * 行列权限授权对象类型
     */
    public static final String TARGET_USER = "user";

    public static final String TARGET_ROLE = "role";

    public static final String TARGET_SYS_PARAMS = "sysParams";

    public static final String EMPTY_JSON_ARRAY = "[]";

    public static final int NAME_MAX = 50;

    public static final int VALUE_MAX = 50;

    public static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 内置系统变量（行权限、SQL 数据集中以 ${sysParams.xxx} 引用）
     */
    public static final String SYS_USER_ID = "sysParams.userId";

    public static final String SYS_USER_NAME = "sysParams.userName";

    public static final String SYS_USER_EMAIL = "sysParams.userEmail";

    public static final String SYS_USER_PHONE = "sysParams.userPhone";

    public static final String SYS_ORG_NAME = "sysParams.orgName";
}
