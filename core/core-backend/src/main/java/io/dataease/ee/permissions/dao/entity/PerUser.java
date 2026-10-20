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
@Table(name = "per_user", uniqueConstraints = {
        @UniqueConstraint(name = "uk_per_user_account", columnNames = "account")
})
@Comment("用户")
public class PerUser {

    @Id
    @Comment("用户ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 255)
    @Comment("账号")
    @Column(name = "account", nullable = false)
    private String account;

    @NotNull
    @Size(max = 255)
    @Comment("姓名")
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 255)
    @Comment("密码摘要（PBKDF2）")
    @Column(name = "pwd")
    private String pwd;

    @Size(max = 255)
    @Comment("邮箱")
    @Column(name = "email")
    private String email;

    @Size(max = 64)
    @Comment("手机号")
    @Column(name = "phone", length = 64)
    private String phone;

    @NotNull
    @ColumnDefault("true")
    @Comment("是否启用")
    @Column(name = "enable", nullable = false)
    private Boolean enable = true;

    @NotNull
    @ColumnDefault("0")
    @Comment("用户来源：0 本地，1 LDAP，2 OIDC，3 CAS，4 企业微信，5 钉钉，6 飞书，7 国际飞书，8 OAuth2，9 SAML")
    @Column(name = "origin", nullable = false)
    private Integer origin = 0;

    @Comment("默认组织ID")
    @Column(name = "default_oid")
    private Long defaultOid;

    @Size(max = 32)
    @Comment("语言")
    @Column(name = "language", length = 32)
    private String language;

    @NotNull
    @ColumnDefault("0")
    @Comment("创建时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;

    @Comment("密码最后修改时间")
    @Column(name = "pwd_update_time")
    private Long pwdUpdateTime;

    @NotNull
    @ColumnDefault("0")
    @Comment("连续登录失败次数")
    @Column(name = "login_fail_count", nullable = false)
    private Integer loginFailCount = 0;

    @Comment("锁定截止时间，为空表示未锁定")
    @Column(name = "lock_until")
    private Long lockUntil;

    @Size(max = 255)
    @Comment("MFA 密钥（对称加密存储）")
    @Column(name = "mfa_secret")
    private String mfaSecret;

    @NotNull
    @ColumnDefault("false")
    @Comment("是否已绑定 MFA")
    @Column(name = "mfa_bound", nullable = false)
    private Boolean mfaBound = false;

    @Comment("最近一次切换的组织ID")
    @Column(name = "last_oid")
    private Long lastOid;
}
