package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

/**
 * 授权记录：主体（用户/角色）对资源（菜单或业务资源）的权限。
 */
@Getter
@Setter
@Entity
@Table(name = "per_auth", indexes = {
        @Index(name = "idx_per_auth_subject", columnList = "subject_type, subject_id, rt_id"),
        @Index(name = "idx_per_auth_resource", columnList = "rt_id, resource_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_auth", columnNames = {"subject_type", "subject_id", "rt_id", "resource_id"})
})
@Comment("授权")
public class PerAuth {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("主体类型：0 用户，1 角色")
    @Column(name = "subject_type", nullable = false)
    private Integer subjectType;

    @NotNull
    @Comment("主体ID")
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @NotNull
    @Comment("资源类型：0 菜单，1 仪表板，2 数据大屏，3 数据集，4 数据源，8 数据填报，10 电子表格")
    @Column(name = "rt_id", nullable = false)
    private Integer rtId;

    @NotNull
    @Comment("资源ID")
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @NotNull
    @ColumnDefault("0")
    @Comment("权限等级：1 查看，2 使用，4 导出，7 管理，9 授权")
    @Column(name = "weight", nullable = false)
    private Integer weight;

    @NotNull
    @ColumnDefault("0")
    @Comment("导出类权限位，十进制各位依次表示 资源导出/数据导出/明细导出")
    @Column(name = "ext", nullable = false)
    private Integer ext = 0;

    @Comment("授权发生的组织ID")
    @Column(name = "oid")
    private Long oid;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
