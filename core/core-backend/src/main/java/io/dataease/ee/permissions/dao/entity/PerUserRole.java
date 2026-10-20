package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_user_role", indexes = {
        @Index(name = "idx_per_user_role_uid", columnList = "uid"),
        @Index(name = "idx_per_user_role_rid", columnList = "rid"),
        @Index(name = "idx_per_user_role_oid", columnList = "oid")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_user_role", columnNames = {"uid", "rid"})
})
@Comment("用户角色关系，用户属于某组织当且仅当其拥有该组织的角色")
public class PerUserRole {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("用户ID")
    @Column(name = "uid", nullable = false)
    private Long uid;

    @NotNull
    @Comment("角色ID")
    @Column(name = "rid", nullable = false)
    private Long rid;

    @NotNull
    @Comment("角色所属组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;
}
