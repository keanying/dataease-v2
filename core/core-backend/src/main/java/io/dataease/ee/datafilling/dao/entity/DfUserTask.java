package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 用户填报任务：子任务分配给某个用户的部分
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_user_task", indexes = {
        @Index(name = "idx_ee_df_user_task_uid", columnList = "uid, end_time"),
        @Index(name = "idx_ee_df_user_task_sub", columnList = "sub_task_id")
})
@Comment("用户填报任务")
public class DfUserTask {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("子任务ID")
    @Column(name = "sub_task_id", nullable = false)
    private Long subTaskId;

    @Comment("任务ID")
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Comment("表单ID")
    @Column(name = "form_id", nullable = false)
    private Long formId;

    @Comment("填报人")
    @Column(name = "uid", nullable = false)
    private Long uid;

    @Comment("下发人")
    @Column(name = "assign_by")
    private Long assignBy;

    @Comment("填报方式：0 新增数据，1 更新数据")
    @Column(name = "fill_type")
    private Integer fillType;

    @Comment("开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("截止时间")
    @Column(name = "end_time")
    private Long endTime;

    @Comment("完成时间")
    @Column(name = "finish_time")
    private Long finishTime;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;
}
