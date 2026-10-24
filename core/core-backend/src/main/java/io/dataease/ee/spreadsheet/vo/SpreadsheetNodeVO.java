package io.dataease.ee.spreadsheet.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.dataease.api.spreadsheet.vo.SpreadsheetTreeVO;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;

/**
 * 电子表格资源树节点。
 * <p>SDK 的 {@link SpreadsheetTreeVO} 缺少前端需要的 weight / extraFlag1 / orgRoot 等字段，并且 pid 未按字符串序列化，
 * 这里在子类中补齐（与核心 {@code BusiNodeVO} 的字段保持一致）。</p>
 */
@Getter
@Setter
public class SpreadsheetNodeVO extends SpreadsheetTreeVO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前用户对该资源的权限等级：0 无（禁用）、1 查看、7 管理、9 授权
     */
    private Integer weight;

    /**
     * 导出类权限位
     */
    private Integer ext;

    private int extraFlag;

    /**
     * 发布状态（叶子节点）：0 未发布，1 已发布，2 已保存未发布
     */
    private int extraFlag1;

    /**
     * 是否为组织同名根目录（虚拟目录）
     */
    private boolean orgRoot;

    private String type = "spreadsheet";

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getPid() {
        return super.getPid();
    }
}
