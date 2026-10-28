package io.dataease.ee.auth.revoke;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_token_revoked", indexes = {
        @Index(name = "idx_per_token_revoked_create", columnList = "create_time"),
        @Index(name = "idx_per_token_revoked_expire", columnList = "expire_time")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_token_revoked_digest", columnNames = {"digest"})
})
@Comment("已注销的登录 / 嵌入式 token")
public class PerTokenRevoked {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 64)
    @Comment("token 摘要")
    @Column(name = "digest", nullable = false, length = 64)
    private String digest;

    @Comment("token 类型：user 登录 token，embedded 嵌入式 token")
    @Column(name = "token_type", length = 20)
    private String tokenType;

    @NotNull
    @Comment("token 过期时间，过期后记录可清理")
    @Column(name = "expire_time", nullable = false)
    private Long expireTime;

    @NotNull
    @Comment("注销时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;
}
