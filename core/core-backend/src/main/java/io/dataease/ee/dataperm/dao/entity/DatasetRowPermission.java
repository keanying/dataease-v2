package io.dataease.ee.dataperm.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "xpack_dataset_row_permissions", indexes = {
        @Index(name = "idx_xpack_ds_row_perm_dsid", columnList = "dataset_id"),
        @Index(name = "idx_xpack_ds_row_perm_target", columnList = "auth_target_type,auth_target_id")
})
@Comment("数据集行权限")
public class DatasetRowPermission {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("数据集ID")
    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @NotNull
    @Size(max = 32)
    @Comment("授权对象类型：user 用户、role 角色、sysParams 系统变量")
    @Column(name = "auth_target_type", nullable = false, length = 32)
    private String authTargetType;

    @Comment("授权对象ID（系统变量规则为空）")
    @Column(name = "auth_target_id")
    private Long authTargetId;

    @Comment("是否启用")
    @Column(name = "enable")
    private Boolean enable;

    @Comment("过滤条件表达式树（JSON）")
    @Column(name = "expression_tree", length = 16777216)
    private String expressionTree;

    @Comment("用户白名单（JSON 字符串数组）")
    @Column(name = "white_list_user", length = 16777216)
    private String whiteListUser;

    @Comment("角色白名单（JSON 字符串数组）")
    @Column(name = "white_list_role", length = 16777216)
    private String whiteListRole;

    @Comment("组织白名单（JSON 字符串数组，保留）")
    @Column(name = "white_list_dept", length = 16777216)
    private String whiteListDept;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
