package io.dataease.ee.plugin.dao;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "xpack_plugin")
@Comment("已安装插件")
public class XpackPlugin {

    @Id
    @Comment("插件ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 255)
    @Comment("插件名称")
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @Size(max = 64)
    @Comment("插件类型：ds/view/df/sync-source/sync-sink")
    @Column(name = "flag", nullable = false, length = 64)
    private String flag;

    @NotNull
    @Size(max = 255)
    @Comment("模块名，与插件包内 moduleName 一致")
    @Column(name = "module_name", nullable = false)
    private String moduleName;

    @Size(max = 64)
    @Comment("版本")
    @Column(name = "version", length = 64)
    private String version;

    @Size(max = 64)
    @Comment("要求的 DataEase 版本")
    @Column(name = "require_version", length = 64)
    private String requireVersion;

    @Size(max = 255)
    @Comment("开发者")
    @Column(name = "developer")
    private String developer;

    @Lob
    @Comment("图标（SVG）")
    @Column(name = "icon", columnDefinition = "longtext")
    private String icon;

    @NotNull
    @Size(max = 512)
    @Comment("插件包文件名")
    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Comment("安装时间")
    @Column(name = "install_time")
    private Long installTime;
}
