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
@Table(name = "xpack_sys_variable_value", indexes = {
        @Index(name = "idx_xpack_sys_var_value_vid", columnList = "sys_variable_id")
})
@Comment("系统变量值：文本变量为可选值列表，数值/时间变量为一条取值范围")
public class SysVariableValue {

    @Id
    @Comment("变量值ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("所属变量ID")
    @Column(name = "sys_variable_id", nullable = false)
    private Long sysVariableId;

    @Size(max = 255)
    @Comment("变量值（文本变量）")
    @Column(name = "variable_value")
    private String value;

    @Size(max = 255)
    @Comment("变量值描述（文本变量）")
    @Column(name = "value_desc")
    private String valueDesc;

    @Size(max = 64)
    @Comment("范围起始值（数值/时间变量）")
    @Column(name = "range_begin", length = 64)
    private String begin;

    @Size(max = 64)
    @Comment("范围结束值（数值/时间变量）")
    @Column(name = "range_end", length = 64)
    private String end;

    @Comment("创建时间")
    @Column(name = "create_time")
    private Long createTime;
}
