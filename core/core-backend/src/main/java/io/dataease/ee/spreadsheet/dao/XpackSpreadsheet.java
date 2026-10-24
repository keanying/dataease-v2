package io.dataease.ee.spreadsheet.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 电子表格（含文件夹）。草稿与发布版本各保存一份 Univer 工作簿 JSON。
 */
@Getter
@Setter
@Entity
@Table(name = "xpack_spreadsheet")
@Comment("电子表格")
public class XpackSpreadsheet {

    @Id
    @Comment("ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("名称")
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Comment("父目录ID，0 为根目录")
    @Column(name = "pid", nullable = false)
    private Long pid;

    @Comment("节点类型：folder 文件夹 / sheet 电子表格")
    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Comment("所属组织ID")
    @Column(name = "org_id")
    private Long orgId;

    @Comment("草稿（编辑中）的 Univer 工作簿 JSON")
    @Column(name = "sheet_data", length = 16777216)
    private String sheetData;

    @Comment("已发布的 Univer 工作簿 JSON")
    @Column(name = "published_data", length = 16777216)
    private String publishedData;

    @Comment("发布状态：0 未发布，1 已发布，2 已保存未发布")
    @Column(name = "status")
    private Integer status;

    @Comment("版本号，每次保存草稿递增")
    @Column(name = "version")
    private Integer version;

    @Comment("备注")
    @Column(name = "remark", length = 1024)
    private String remark;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新人ID")
    @Column(name = "update_by")
    private Long updateBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;

    @Comment("发布人ID")
    @Column(name = "publish_by")
    private Long publishBy;

    @Comment("发布时间")
    @Column(name = "publish_time")
    private Long publishTime;
}
