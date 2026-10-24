package io.dataease.ee.spreadsheet.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.dataease.api.spreadsheet.vo.SpreadsheetVO;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;

/**
 * 电子表格详情：在 SDK {@link SpreadsheetVO} 基础上让 pid / orgId 以字符串输出，避免前端精度丢失。
 */
@Getter
@Setter
public class SpreadsheetDetailVO extends SpreadsheetVO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前用户对该资源的权限等级
     */
    private Integer weight;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getPid() {
        return super.getPid();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getOrgId() {
        return super.getOrgId();
    }
}
