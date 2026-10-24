package io.dataease.ee.sso.dao;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

/**
 * 认证设置（LDAP/OIDC/CAS/OAuth2/SAML2）与平台对接（钉钉/飞书/国际飞书/企业微信）配置
 */
@Getter
@Setter
@Entity
@Table(name = "ee_auth_config", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ee_auth_config_name", columnNames = {"name"})
})
@Comment("认证与平台对接配置")
public class EeAuthConfig {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 32)
    @Comment("配置名称：ldap、oidc、cas、oauth2、saml2、dingtalk、lark、larksuite、wecom")
    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @NotNull
    @ColumnDefault("false")
    @Comment("是否启用")
    @Column(name = "enable", nullable = false)
    private Boolean enable = false;

    @NotNull
    @ColumnDefault("false")
    @Comment("最近一次校验是否通过")
    @Column(name = "valid", nullable = false)
    private Boolean valid = false;

    @Comment("配置内容（JSON，对称加密存储）")
    @Column(name = "config", length = 16777216)
    private String config;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;

    @Comment("更新人")
    @Column(name = "update_by")
    private Long updateBy;
}
