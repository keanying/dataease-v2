package io.dataease.ee.datafilling;

/**
 * 数据填报模块常量
 */
public final class DfConstants {

    private DfConstants() {
    }

    public static final String NODE_FOLDER = "folder";

    public static final String NODE_FORM = "form";

    /**
     * 内置数据引擎对应的数据源 ID
     */
    public static final long ENGINE_DS_ID = -1L;

    /**
     * 自动创建的数据表主键列名
     */
    public static final String DEFAULT_PK = "id";

    /**
     * 提交日志操作类型
     */
    public static final int OP_DELETE = 0;

    public static final int OP_INSERT = 1;

    public static final int OP_UPDATE = 2;

    public static final int OP_IMPORT = 3;

    /**
     * 填报方式：0 新增数据，1 更新数据
     */
    public static final int FILL_APPEND = 0;

    public static final int FILL_UPDATE = 1;

    /**
     * 任务状态：0 等待下发，1 下发中，2 已停止，3 已完成
     */
    public static final int TASK_WAITING = 0;

    public static final int TASK_RUNNING = 1;

    public static final int TASK_STOPPED = 2;

    public static final int TASK_FINISHED = 3;

    /**
     * 执行状态：1 执行中，2 成功，3 失败
     */
    public static final int EXEC_RUNNING = 1;

    public static final int EXEC_SUCCESS = 2;

    public static final int EXEC_FAILED = 3;

    /**
     * 任务项状态
     */
    public static final int ITEM_OPEN = 0;

    public static final int ITEM_FINISHED = 1;

    public static final String WS_REPORT_NOTICE = "/report-notice";

    public static final String WS_MSG_TOPIC = "/web-msg-topic";
}
