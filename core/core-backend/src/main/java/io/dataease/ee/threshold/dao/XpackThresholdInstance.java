package io.dataease.ee.threshold.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "xpack_threshold_instance")
@Comment("阈值告警检测记录")
public class XpackThresholdInstance {

    @Id
    @Comment("记录ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("告警ID")
    @Column(name = "threshold_id", nullable = false)
    private Long thresholdId;

    @Comment("检测时间")
    @Column(name = "exec_time")
    private Long execTime;

    @Comment("是否触发告警")
    @Column(name = "status")
    private Boolean status;

    @Comment("发送的消息内容（HTML）")
    @Column(name = "content", length = 16777216)
    private String content;

    @Comment("检测/发送信息（错误详情）")
    @Column(name = "msg", length = 16777216)
    private String msg;

    @Comment("所属组织")
    @Column(name = "oid")
    private Long oid;
}
