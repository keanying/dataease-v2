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
@Table(name = "xpack_sys_variable_user", indexes = {
        @Index(name = "idx_xpack_sys_var_user_uid", columnList = "user_id"),
        @Index(name = "idx_xpack_sys_var_user_vid", columnList = "sys_variable_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_xpack_sys_var_user", columnNames = {"user_id", "sys_variable_id"})
})
@Comment("用户的系统变量取值")
public class SysVariableUser {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("用户ID")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Comment("变量ID")
    @Column(name = "sys_variable_id", nullable = false)
    private Long sysVariableId;

    @Size(max = 255)
    @Comment("数值/时间变量的取值")
    @Column(name = "variable_value")
    private String variableValue;

    @Comment("文本变量选中的变量值ID（JSON 字符串数组）")
    @Column(name = "variable_value_ids", length = 16777216)
    private String variableValueIds;

    @Comment("更新时间")
    @Column(name = "update_time")
    private Long updateTime;
}
