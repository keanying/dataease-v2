package io.dataease.ee.report.dao;

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
@Table(name = "xpack_report_task")
@Comment("定时报告任务")
public class XpackReportTask {

    @Id
    @Comment("任务ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("任务名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("报告标题")
    @Column(name = "title")
    private String title;

    @Comment("报告正文（HTML）")
    @Column(name = "content", length = 16777216)
    private String content;

    @Comment("资源类型：0 仪表板 1 数据大屏")
    @Column(name = "rtid")
    private Integer rtid;

    @Comment("仪表板/数据大屏ID")
    @Column(name = "rid")
    private Long rid;

    @Comment("是否显示水印")
    @Column(name = "show_watermark")
    private Boolean showWatermark;

    @Comment("截图格式：0 jpeg 1 pdf")
    @Column(name = "format")
    private Integer format;

    @Comment("附带数据的图表ID（JSON数组）")
    @Column(name = "view_ids", length = 16777216)
    private String viewIds;

    @Comment("图表数据范围：0 全部数据 1 图表展示数据")
    @Column(name = "view_data_range")
    private Integer viewDataRange;

    @Comment("截图分辨率，如 1920 * 1080")
    @Column(name = "pixel", length = 64)
    private String pixel;

    @Comment("个人发送渠道（JSON数组：1 邮件 2 企业微信 3 钉钉 4 飞书 5 国际飞书）")
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

    @Comment("钉钉群（JSON数组）")
    @Column(name = "dingtalk_groups", length = 16777216)
    private String dingtalkGroups;

    @Comment("飞书群（JSON数组）")
    @Column(name = "lark_groups", length = 16777216)
    private String larkGroups;

    @Comment("国际飞书群（JSON数组）")
    @Column(name = "larksuite_groups", length = 16777216)
    private String larksuiteGroups;

    @Comment("页面加载超时（秒）")
    @Column(name = "ext_wait_time")
    private Integer extWaitTime;

    @Comment("加载完成后等待渲染时间（秒）")
    @Column(name = "render_time")
    private Integer renderTime;

    @Comment("频率类型：0 cron 1 每天 2 每周 3 每月")
    @Column(name = "rate_type")
    private Integer rateType;

    @Comment("频率值")
    @Column(name = "rate_val")
    private String rateVal;

    @Comment("开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("结束时间")
    @Column(name = "end_time")
    private Long endTime;

    @Comment("失败是否重试")
    @Column(name = "retry_enable")
    private Boolean retryEnable;

    @Comment("重试次数")
    @Column(name = "retry_limit")
    private Integer retryLimit;

    @Comment("重试间隔（分钟）")
    @Column(name = "retry_interval")
    private Integer retryInterval;

    @Comment("数据权限：0 按创建人 1 按接收人")
    @Column(name = "data_permission")
    private Integer dataPermission;

    @Comment("任务状态：0 等待 1 执行中 2 停止 3 已结束")
    @Column(name = "status")
    private Integer status;

    @Comment("最近执行时间")
    @Column(name = "last_exec_time")
    private Long lastExecTime;

    @Comment("最近执行状态：1 执行中 2 成功 3 失败")
    @Column(name = "last_exec_status")
    private Integer lastExecStatus;

    @Comment("所属组织ID")
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
