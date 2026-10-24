package io.dataease.ee.dataperm.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "xpack_sys_variable")
@Comment("系统变量")
public class SysVariable {

    @Id
    @Comment("变量ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Size(max = 64)
    @Comment("变量名称")
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @NotNull
    @Size(max = 16)
    @Comment("变量类型：text 文本、num 数值、time 时间")
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Size(max = 255)
    @Comment("描述")
    @Column(name = "description")
    private String description;

    @Comment("创建人ID")
    @Column(name = "create_by")
    private Long createBy;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
