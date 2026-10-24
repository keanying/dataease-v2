package io.dataease.ee.sync.dao;

import io.dataease.dao.auto.interceptor.EncryptDecryptConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 同步数据源
 */
@Getter
@Setter
@Entity
@Table(name = "per_sync_datasource")
@Comment("同步管理数据源")
public class PerSyncDatasource {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @Comment("名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Comment("描述")
    @Column(name = "description", length = 512)
    private String description;

    @Comment("数据源类型")
    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Comment("数据源角色：1 源端，2 目标端")
    @Column(name = "datasource_role", nullable = false)
    private Integer datasourceRole;

    @Comment("连接配置（JSON，加密存储）")
    @Column(name = "configuration", length = 16777216, nullable = false)
    @Convert(converter = EncryptDecryptConverter.class)
    private String configuration;

    @Comment("状态：Success / Error")
    @Column(name = "status", length = 32)
    private String status;

    @Comment("状态说明（异常信息）")
    @Column(name = "status_remark", length = 2048)
    private String statusRemark;

    @Comment("所属组织ID")
    @Column(name = "oid", nullable = false)
    private Long oid;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;

    @Comment("更新人ID")
    @Column(name = "update_by")
    private Long updateBy;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
