package io.dataease.ee.spreadsheet.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 电子表格引用的数据集（从工作簿插件配置中解析），用于取数鉴权与血缘。
 */
@Getter
@Setter
@Entity
@Table(name = "xpack_spreadsheet_dataset")
@Comment("电子表格引用的数据集")
public class XpackSpreadsheetDataset {

    @Id
    @Comment("ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("电子表格ID")
    @Column(name = "spreadsheet_id", nullable = false)
    private Long spreadsheetId;

    @Comment("数据集ID")
    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Comment("是否为发布版本的引用：true 发布版本，false 草稿")
    @Column(name = "published", nullable = false)
    private Boolean published;
}
