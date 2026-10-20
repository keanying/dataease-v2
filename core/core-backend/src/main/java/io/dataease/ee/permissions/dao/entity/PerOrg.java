package io.dataease.ee.permissions.dao.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "per_org", indexes = {
        @Index(name = "idx_per_org_pid", columnList = "pid")
})
@Comment("组织")
public class PerOrg {

    @Id
    @Comment("组织ID")
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Comment("上级组织ID，根组织为0")
    @Column(name = "pid", nullable = false)
    private Long pid;

    @NotNull
    @Size(max = 255)
    @Comment("组织名称")
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 1024)
    @Comment("寻根路径，逗号分隔的祖先ID（含自身），如 1,5,8")
    @Column(name = "root_path", length = 1024)
    private String rootPath;

    @NotNull
    @ColumnDefault("0")
    @Comment("创建时间")
    @Column(name = "create_time", nullable = false)
    private Long createTime;

    @Comment("创建人")
    @Column(name = "creator")
    private Long creator;
}
