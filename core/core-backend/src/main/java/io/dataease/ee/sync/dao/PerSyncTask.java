package io.dataease.ee.sync.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 同步任务
 */
@Getter
@Setter
@Entity
@Table(name = "per_sync_task")
@Comment("同步管理任务")
public class PerSyncTask {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("任务名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("描述")
    @Column(name = "description", length = 512)
    private String description;

    @Comment("任务状态：waiting/running/suspend/done/unexecuted")
    @Column(name = "status", length = 32)
    private String status;

    @Comment("调度方式：NONE/CRON/FIX_RATE")
    @Column(name = "scheduler_type", length = 32)
    private String schedulerType;

    @Comment("调度配置：cron 表达式或间隔（如 30m）")
    @Column(name = "scheduler_conf", length = 255)
    private String schedulerConf;

    @Comment("调度开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("调度结束时间")
    @Column(name = "stop_time")
    private Long stopTime;

    @Comment("执行超时时间（秒），0 表示不限制")
    @Column(name = "executor_timeout")
    private Long executorTimeout;

    @Comment("失败重试次数")
    @Column(name = "executor_fail_retry_count")
    private Long executorFailRetryCount;

    @Comment("源数据源ID")
    @Column(name = "source_ds_id")
    private Long sourceDsId;

    @Comment("目标数据源ID")
    @Column(name = "target_ds_id")
    private Long targetDsId;

    @Comment("目标表名")
    @Column(name = "target_table", length = 128)
    private String targetTable;

    @Comment("源端配置（JSON）")
    @Column(name = "source_config", length = 16777216)
    private String sourceConfig;

    @Comment("目标端配置（JSON）")
    @Column(name = "target_config", length = 16777216)
    private String targetConfig;

    @Comment("是否增量同步")
    @Column(name = "increment_sync")
    private Boolean incrementSync;

    @Comment("目标表是否已由任务创建（创建后表结构不可修改）")
    @Column(name = "table_created")
    private Boolean tableCreated;

    @Comment("上次执行时间")
    @Column(name = "trigger_last_time")
    private Long triggerLastTime;

    @Comment("下次执行时间")
    @Column(name = "trigger_next_time")
    private Long triggerNextTime;

    @Comment("上次执行结果：SUCCESS/FAIL/...")
    @Column(name = "last_execute_status", length = 32)
    private String lastExecuteStatus;

    @Comment("最近一次执行日志ID")
    @Column(name = "last_log_id")
    private Long lastLogId;

    @Comment("所属组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;

    @Comment("更新人ID")
    @Column(name = "update_by")
    private Long updateBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
