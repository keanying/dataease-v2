package io.dataease.ee.sync.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 同步任务执行日志
 */
@Getter
@Setter
@Entity
@Table(name = "per_sync_task_log")
@Comment("同步管理任务执行日志")
public class PerSyncTaskLog {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("任务ID")
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Comment("任务名称（执行时快照）")
    @Column(name = "task_name")
    private String taskName;

    @Comment("所属组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @Comment("触发方式：SCHEDULE/MANUAL")
    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    @Comment("执行开始时间")
    @Column(name = "executor_start_time")
    private Long executorStartTime;

    @Comment("执行结束时间")
    @Column(name = "executor_end_time")
    private Long executorEndTime;

    @Comment("执行状态：RUNNING/SUCCESS/FAIL/TERMINATION/CONNECTION_LOST")
    @Column(name = "status", length = 32)
    private String status;

    @Comment("执行结果说明")
    @Column(name = "executor_msg", length = 4000)
    private String executorMsg;

    @Comment("执行节点")
    @Column(name = "executor_address", length = 255)
    private String executorAddress;

    @Comment("同步行数")
    @Column(name = "row_count")
    private Long rowCount;

    @Comment("执行日志内容")
    @Column(name = "log_content", length = 16777216)
    private String logContent;
}
