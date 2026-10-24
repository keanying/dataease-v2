package io.dataease.ee.setting.apikey.dao;

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
@Table(name = "per_api_key")
@Comment("用户 API Key")
public class PerApiKey {

    @Id
    @Comment("ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("所属用户ID")
    @Column(name = "user_id", nullable = false)
    private Long uid;

    @NotNull
    @Size(max = 64)
    @Comment("accessKey")
    @Column(name = "access_key", nullable = false, length = 64, unique = true)
    private String accessKey;

    @NotNull
    @Size(max = 128)
    @Comment("accessSecret（JWT 签名密钥）")
    @Column(name = "access_secret", nullable = false, length = 128)
    private String accessSecret;

    @NotNull
    @Comment("是否启用")
    @Column(name = "enable", nullable = false)
    private Boolean enable;

    @NotNull
    @Comment("创建时间（毫秒）")
    @Column(name = "create_time", nullable = false)
    private Long createTime;
}
