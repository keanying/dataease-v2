package io.dataease.ee.setting.embedded.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_embedded")
@Comment("嵌入式应用")
public class PerEmbedded {

    @Id
    @Comment("ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 64)
    @Comment("应用名称")
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @NotNull
    @Size(max = 64)
    @Comment("应用ID（嵌入式 token 声明 appId）")
    @Column(name = "app_id", nullable = false, length = 64, unique = true)
    private String appId;

    @NotNull
    @Size(max = 64)
    @Comment("应用密钥（嵌入式 token 的 HS256 签名密钥）")
    @Column(name = "app_secret", nullable = false, length = 64)
    private String appSecret;

    @NotNull
    @Size(max = 255)
    @Comment("允许嵌入的域名，多个以逗号分隔")
    @Column(name = "allow_domain", nullable = false, length = 255)
    private String domain;

    @NotNull
    @Comment("密钥长度")
    @Column(name = "secret_length", nullable = false)
    private Integer secretLength;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @NotNull
    @Comment("创建时间（毫秒）")
    @Column(name = "create_time", nullable = false)
    private Long createTime;

    @Comment("更新时间（毫秒）")
    @Column(name = "update_time")
    private Long updateTime;
}
