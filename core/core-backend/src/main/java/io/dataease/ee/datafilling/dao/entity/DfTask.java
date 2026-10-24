package io.dataease.ee.datafilling.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 数据填报任务（下发计划）
 */
@Getter
@Setter
@Entity
@Table(name = "ee_df_task", indexes = {
        @Index(name = "idx_ee_df_task_form", columnList = "form_id")
})
@Comment("数据填报任务")
public class DfTask {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("表单ID")
    @Column(name = "form_id", nullable = false)
    private Long formId;

    @Comment("任务名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("填报方式：0 新增数据，1 更新数据")
    @Column(name = "fill_type")
    private Integer fillType;

    @Comment("接收用户ID列表（JSON）")
    @Column(name = "uid_list", length = 16777216)
    private String uidList;

    @Comment("接收角色ID列表（JSON）")
    @Column(name = "rid_list", length = 16777216)
    private String ridList;

    @Comment("更新数据时匹配对象：0 账号，1 姓名")
    @Column(name = "fit_type")
    private Integer fitType;

    @Comment("更新数据时用于匹配用户的列")
    @Column(name = "fit_column")
    private String fitColumn;

    @Comment("表单模板设置（JSON）")
    @Column(name = "form_ext_setting", length = 16777216)
    private String formExtSetting;

    @Comment("数据过滤设置（JSON）")
    @Column(name = "form_filter_setting", length = 16777216)
    private String formFilterSetting;

    @Comment("频率：0 一次性，1 每天，2 每周，3 每月")
    @Column(name = "rate_type")
    private Integer rateType;

    @Comment("一次性下发方式：0 立即，1 定时")
    @Column(name = "one_time_type")
    private Integer oneTimeType;

    @Comment("周期设置")
    @Column(name = "rate_val")
    private String rateVal;

    @Comment("开始时间")
    @Column(name = "start_time")
    private Long startTime;

    @Comment("结束时间")
    @Column(name = "end_time")
    private Long endTime;

    @Comment("每次下发的填报时长")
    @Column(name = "publish_range_time")
    private Integer publishRangeTime;

    @Comment("填报时长单位：-1 小时，0 天，1 周，2 月")
    @Column(name = "publish_range_time_type")
    private Integer publishRangeTimeType;

    @Comment("通知渠道（JSON）")
    @Column(name = "reci_flag_list")
    private String reciFlagList;

    @Comment("通知内容类型")
    @Column(name = "msg_type")
    private Integer msgType;

    @Comment("通知标题")
    @Column(name = "msg_title")
    private String msgTitle;

    @Comment("通知内容")
    @Column(name = "msg_content", length = 16777216)
    private String msgContent;

    @Comment("状态：0 等待，1 下发中，2 已停止，3 已完成")
    @Column(name = "status")
    private Integer status;

    @Comment("最近执行状态：1 执行中，2 成功，3 失败")
    @Column(name = "last_exec_status")
    private Integer lastExecStatus;

    @Comment("最近执行时间")
    @Column(name = "last_exec_time")
    private Long lastExecTime;

    @Comment("最近一次失败信息")
    @Column(name = "error_msg", length = 16777216)
    private String errorMsg;

    @Comment("所属组织ID")
    @Column(name = "org_id")
    private Long orgId;

    @Comment("创建人")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新人")
    @Column(name = "update_by")
    private Long updateBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
