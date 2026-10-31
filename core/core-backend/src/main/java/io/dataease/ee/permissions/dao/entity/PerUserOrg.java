package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 用户与组织的成员关系：一个用户可以属于多个组织。
 * per_user.default_oid 为用户的主组织，per_user.last_oid 为最近一次切换到的组织，二者都应是用户所属的组织。
 */
@Getter
@Setter
@Entity
@Table(name = "per_user_org", indexes = {
        @Index(name = "idx_per_user_org_uid", columnList = "uid"),
        @Index(name = "idx_per_user_org_oid", columnList = "oid")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_user_org", columnNames = {"uid", "oid"})
})
@Comment("用户所属组织（多组织成员关系）")
public class PerUserOrg {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("用户ID")
    @Column(name = "uid", nullable = false)
    private Long uid;

    @NotNull
    @Comment("组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @Comment("加入时间")
    @Column(name = "create_time")
    private Long createTime;
}
