package io.dataease.ee.log.dao;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 操作日志
 */
@Getter
@Setter
@Entity
@Table(name = "xpack_log", indexes = {
        @Index(name = "idx_xpack_log_time", columnList = "create_time"),
        @Index(name = "idx_xpack_log_oid", columnList = "oid"),
        @Index(name = "idx_xpack_log_uid", columnList = "uid")
})
@Comment("操作日志")
public class XpackLog {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("组织ID")
    @Column(name = "oid")
    private Long oid;

    @Comment("操作人ID")
    @Column(name = "uid")
    private Long uid;

    @Size(max = 255)
    @Comment("操作人名称（记录时快照）")
    @Column(name = "user_name")
    private String userName;

    @Comment("资源类型（LogST）")
    @Column(name = "source_type")
    private Integer sourceType;

    @Comment("操作类型（LogOT）")
    @Column(name = "operate_type")
    private Integer operateType;

    @Size(max = 64)
    @Comment("资源ID")
    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Size(max = 1024)
    @Comment("资源名称/路径")
    @Column(name = "op_detail", length = 1024)
    private String opDetail;

    @Size(max = 64)
    @Comment("客户端IP")
    @Column(name = "ip", length = 64)
    private String ip;

    @Size(max = 16)
    @Comment("客户端类型：pc/mobile")
    @Column(name = "client", length = 16)
    private String client;

    @Comment("是否成功")
    @Column(name = "success")
    private Boolean success;

    @Size(max = 2000)
    @Comment("失败信息")
    @Column(name = "msg", length = 2000)
    private String msg;

    @Comment("操作时间（毫秒）")
    @Column(name = "create_time")
    private Long createTime;
}
