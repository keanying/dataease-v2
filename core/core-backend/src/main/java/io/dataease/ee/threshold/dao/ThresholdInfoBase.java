package io.dataease.ee.threshold.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 阈值告警字段（正式表 xpack_threshold_info 与镜像表 xpack_threshold_info_snapshot 结构相同，ID 一致）
 */
@Getter
@Setter
@MappedSuperclass
public abstract class ThresholdInfoBase {

    @Id
    @Comment("告警ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("告警名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("是否启用")
    @Column(name = "enable")
    private Boolean enable;

    @Comment("检测频率：0 每小时 1 每天 2 每周 3 每月")
    @Column(name = "rate_type")
    private Integer rateType;

    @Comment("频率值：每小时为分钟，其余为 2021-08-DD HH:mm:ss")
    @Column(name = "rate_value")
    private String rateValue;

    @Comment("仪表板/数据大屏ID")
    @Column(name = "resource_id")
    private Long resourceId;

    @Comment("资源类型：dashboard / dataV")
    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Comment("图表ID")
    @Column(name = "chart_id")
    private Long chartId;

    @Comment("图表类型")
    @Column(name = "chart_type", length = 100)
    private String chartType;

    @Comment("告警规则（JSON）")
    @Column(name = "threshold_rules", length = 16777216)
    private String thresholdRules;

    @Comment("个人发送渠道（JSON数组）")
    @Column(name = "reci_flags", length = 255)
    private String reciFlags;

    @Comment("接收用户ID（JSON数组）")
    @Column(name = "uids", length = 16777216)
    private String uids;

    @Comment("接收角色ID（JSON数组）")
    @Column(name = "rids", length = 16777216)
    private String rids;

    @Comment("额外接收邮箱（JSON数组）")
    @Column(name = "emails", length = 16777216)
    private String emails;

    @Comment("飞书群（JSON数组）")
    @Column(name = "lark_groups", length = 16777216)
    private String larkGroups;

    @Comment("国际飞书群（JSON数组）")
    @Column(name = "larksuite_groups", length = 16777216)
    private String larksuiteGroups;

    @Comment("Webhook ID（JSON数组）")
    @Column(name = "webhooks", length = 16777216)
    private String webhooks;

    @Comment("消息类型：0 默认模板 1 自定义")
    @Column(name = "msg_type")
    private Integer msgType;

    @Comment("消息标题")
    @Column(name = "msg_title")
    private String msgTitle;

    @Comment("消息内容（HTML，含占位符）")
    @Column(name = "msg_content", length = 16777216)
    private String msgContent;

    @Comment("持续异常时是否重复发送")
    @Column(name = "repeat_send")
    private Boolean repeatSend;

    @Comment("是否展示字段值")
    @Column(name = "show_field_value")
    private Boolean showFieldValue;

    @Comment("最近检测是否异常")
    @Column(name = "status")
    private Boolean status;

    @Comment("最近检测时间")
    @Column(name = "last_exec_time")
    private Long lastExecTime;

    @Comment("所属组织")
    @Column(name = "oid")
    private Long oid;

    @Comment("创建人")
    @Column(name = "creator")
    private Long creator;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新人")
    @Column(name = "updater")
    private Long updater;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
