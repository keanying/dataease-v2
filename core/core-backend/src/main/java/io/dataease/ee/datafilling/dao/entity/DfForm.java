package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 数据填报表单 / 目录
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_form", indexes = {
        @Index(name = "idx_ee_df_form_pid", columnList = "pid"),
        @Index(name = "idx_ee_df_form_org", columnList = "org_id")
})
@Comment("数据填报表单")
public class DfForm {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("父级目录ID，0 为根目录")
    @Column(name = "pid", nullable = false)
    private Long pid;

    @Comment("名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("节点类型：folder 目录，form 表单")
    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Comment("数据表名")
    @Column(name = "table_name")
    private String tableName;

    @Comment("数据源ID，-1 为内置数据引擎")
    @Column(name = "datasource")
    private Long datasource;

    @Comment("表单内容（JSON）")
    @Column(name = "forms", length = 16777216)
    private String forms;

    @Comment("是否创建索引")
    @Column(name = "create_index")
    private Boolean createIndex;

    @Comment("索引配置（JSON）")
    @Column(name = "table_indexes", length = 16777216)
    private String tableIndexes;

    @Comment("是否绑定已有数据表")
    @Column(name = "use_exists_table")
    private Boolean useExistsTable;

    @Comment("数据表主键列名")
    @Column(name = "primary_key")
    private String primaryKey;

    @Comment("所属组织ID")
    @Column(name = "org_id")
    private Long orgId;

    @Comment("创建人")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新人")
    @Column(name = "update_by")
    private Long updateBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
