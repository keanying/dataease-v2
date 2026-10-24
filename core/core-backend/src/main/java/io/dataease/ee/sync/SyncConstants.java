package io.dataease.ee.sync;

import java.util.Set;

/**
 * 数据同步（同步管理）模块常量
 */
public final class SyncConstants {

    private SyncConstants() {
    }

    /**
     * 菜单：同步概览、数据源、任务
     */
    public static final Long MENU_SUMMARY = 22L;
    public static final Long MENU_DATASOURCE = 23L;
    public static final Long MENU_TASK = 24L;

    /**
     * 数据源状态
     */
    public static final String DS_SUCCESS = "Success";
    public static final String DS_ERROR = "Error";

    /**
     * 调度方式
     */
    public static final String SCHEDULER_NONE = "NONE";
    public static final String SCHEDULER_CRON = "CRON";
    public static final String SCHEDULER_FIX_RATE = "FIX_RATE";

    /**
     * 任务状态（小写）
     */
    public static final String TASK_WAITING = "waiting";
    public static final String TASK_RUNNING = "running";
    public static final String TASK_SUSPEND = "suspend";
    public static final String TASK_DONE = "done";
    public static final String TASK_UNEXECUTED = "unexecuted";

    /**
     * 日志状态（大写）
     */
    public static final String LOG_SUCCESS = "SUCCESS";
    public static final String LOG_FAIL = "FAIL";
    public static final String LOG_RUNNING = "RUNNING";
    public static final String LOG_CONNECTION_LOST = "CONNECTION_LOST";
    public static final String LOG_TERMINATION = "TERMINATION";

    public static final Set<String> LOG_FINISHED = Set.of(LOG_SUCCESS, LOG_FAIL, LOG_CONNECTION_LOST, LOG_TERMINATION);

    /**
     * 源端抽取方式：0 表，1 SQL
     */
    public static final String EXTRACT_TABLE = "0";
    public static final String EXTRACT_SQL = "1";

    public static final String SWITCH_ON = "on";

    /**
     * 每批读取/写入的行数
     */
    public static final int BATCH_SIZE = 5000;

    /**
     * 目标表名规则
     */
    public static final String TABLE_NAME_REGEX = "^[a-zA-Z][a-zA-Z0-9_]*$";

    /**
     * 执行日志中“没有新内容”的占位
     */
    public static final String NO_NEW_LOG = "...";
}
