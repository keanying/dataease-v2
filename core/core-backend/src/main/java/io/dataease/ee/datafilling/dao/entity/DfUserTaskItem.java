package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 用户填报任务项：更新数据方式下为分配给用户的一行数据，新增数据方式下为用户新增的一行数据
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_user_task_item", indexes = {
        @Index(name = "idx_ee_df_item_user_task", columnList = "user_task_id"),
        @Index(name = "idx_ee_df_item_form", columnList = "form_id, uid")
})
@Comment("用户填报任务项")
public class DfUserTaskItem {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("用户填报任务ID")
    @Column(name = "user_task_id", nullable = false)
    private Long userTaskId;

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

    @Comment("数据主键值")
    @Column(name = "data_id")
    private String dataId;

    @Comment("状态：0 未填报，1 已填报")
    @Column(name = "status")
    private Integer status;

    @Comment("完成时间")
    @Column(name = "finish_time")
    private Long finishTime;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;
}
