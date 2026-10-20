package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_role", indexes = {
        @Index(name = "idx_per_role_oid", columnList = "oid")
})
@Comment("角色")
public class PerRole {

    @Id
    @Comment("角色ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("所属组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @NotNull
    @Size(max = 255)
    @Comment("角色名称")
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @ColumnDefault("0")
    @Comment("角色类型：0 普通用户，7 数据分析师，9 组织管理员")
    @Column(name = "type_code", nullable = false)
    private Integer typeCode = 0;

    @NotNull
    @ColumnDefault("false")
    @Comment("是否系统内置角色")
    @Column(name = "root", nullable = false)
    private Boolean root = false;

    @NotNull
    @ColumnDefault("false")
    @Comment("是否只读（权限不可编辑）")
    @Column(name = "readonly", nullable = false)
    private Boolean readonly = false;

    @Size(max = 1024)
    @Comment("描述")
    @Column(name = "description", length = 1024)
    private String description;

    @NotNull
    @ColumnDefault("0")
    @Comment("创建时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;
}
