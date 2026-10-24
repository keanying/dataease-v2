package io.dataease.ee.report.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "xpack_report_instance")
@Comment("定时报告执行记录")
public class XpackReportInstance {

    @Id
    @Comment("记录ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("报告任务ID")
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Comment("开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("结束时间")
    @Column(name = "end_time")
    private Long endTime;

    @Comment("执行状态：1 执行中 2 成功 3 失败")
    @Column(name = "exec_status")
    private Integer execStatus;

    @Comment("执行信息（错误详情）")
    @Column(name = "msg", length = 16777216)
    private String msg;

    @Comment("触发方式：0 定时 1 立即执行 2 重试")
    @Column(name = "trigger_type")
    private Integer triggerType;

    @Comment("所属组织ID")
    @Column(name = "oid")
    private Long oid;
}
