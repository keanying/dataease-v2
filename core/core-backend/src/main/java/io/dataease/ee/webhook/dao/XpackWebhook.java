package io.dataease.ee.webhook.dao;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * Webhook 配置（按组织隔离）
 */
@Getter
@Setter
@Entity
@Table(name = "xpack_webhook", indexes = {
        @Index(name = "idx_xpack_webhook_oid", columnList = "oid")
})
@Comment("Webhook")
public class XpackWebhook {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Size(max = 255)
    @Comment("名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 255)
    @Comment("回调地址")
    @Column(name = "url", nullable = false)
    private String url;

    @Size(max = 255)
    @Comment("签名密钥")
    @Column(name = "secret")
    private String secret;

    @Size(max = 64)
    @Comment("请求体类型：application/json 或 application/x-www-form-urlencoded")
    @Column(name = "content_type", length = 64)
    private String contentType;

    @Comment("是否校验 HTTPS 证书")
    @Column(name = "ssl_verify")
    private Boolean ssl;

    @Comment("消息模板（JSON，支持 ${title}/${content}/${messageId} 等占位符）")
    @Column(name = "msg_template", length = 65535)
    private String msgTemplate;

    @Comment("组织ID")
    @Column(name = "oid")
    private Long oid;

    @Comment("创建人")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
