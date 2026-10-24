package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 数据填报提交日志
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_commit_log", indexes = {
        @Index(name = "idx_ee_df_log_form", columnList = "form_id, commit_time"),
        @Index(name = "idx_ee_df_log_data", columnList = "form_id, data_id")
})
@Comment("数据填报提交日志")
public class DfCommitRecord {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("表单ID")
    @Column(name = "form_id", nullable = false)
    private Long formId;

    @Comment("数据主键值")
    @Column(name = "data_id")
    private String dataId;

    @Comment("操作：0 删除，1 插入，2 更新，3 批量导入")
    @Column(name = "operate", nullable = false)
    private Integer operate;

    @Comment("提交人")
    @Column(name = "commit_by")
    private Long commitBy;

    @Comment("提交时间")
    @Column(name = "commit_time", nullable = false)
    private Long commitTime;

    @Comment("影响行数（批量导入）")
    @Column(name = "data_count")
    private Integer count;

    @Comment("关联的用户填报任务ID")
    @Column(name = "user_task_id")
    private Long userTaskId;
}
