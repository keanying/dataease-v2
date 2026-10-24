package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 数据填报子任务：任务的一次下发
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_sub_task", indexes = {
        @Index(name = "idx_ee_df_sub_task_task", columnList = "task_id")
})
@Comment("数据填报子任务")
public class DfSubTask {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("任务ID")
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Comment("表单ID")
    @Column(name = "form_id", nullable = false)
    private Long formId;

    @Comment("填报方式：0 新增数据，1 更新数据")
    @Column(name = "fill_type")
    private Integer fillType;

    @Comment("开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("截止时间")
    @Column(name = "end_time")
    private Long endTime;

    @Comment("下发状态：1 执行中，2 成功，3 失败")
    @Column(name = "exec_status")
    private Integer execStatus;

    @Comment("失败信息")
    @Column(name = "error_msg", length = 16777216)
    private String errorMsg;

    @Comment("下发人")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;
}
