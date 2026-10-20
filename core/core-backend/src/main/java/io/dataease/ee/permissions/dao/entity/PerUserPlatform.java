package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_user_platform", indexes = {
        @Index(name = "idx_per_user_platform_uid", columnList = "uid")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_user_platform", columnNames = {"origin", "platform_uid"})
})
@Comment("用户与第三方平台账号的绑定")
public class PerUserPlatform {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("用户ID")
    @Column(name = "uid", nullable = false)
    private Long uid;

    @NotNull
    @Comment("平台来源，取值同 per_user.origin")
    @Column(name = "origin", nullable = false)
    private Integer origin;

    @NotNull
    @Size(max = 255)
    @Comment("平台侧用户标识")
    @Column(name = "platform_uid", nullable = false)
    private String platformUid;

    @Comment("绑定时间")
    @Column(name = "create_time")
    private Long createTime;
}
