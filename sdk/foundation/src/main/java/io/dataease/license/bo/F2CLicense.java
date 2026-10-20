package io.dataease.license.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 许可证信息。自研企业版不依赖外部签发的许可证，字段保留以兼容前端“关于”页面展示。
 */
@Data
public class F2CLicense implements Serializable {

    @Serial
    private static final long serialVersionUID = 4580926145938318417L;

    private String corporation;

    private String expired;

    private Long count;

    private String version;

    private String edition;

    private String serialNo;

    private String remark;

    private String isv;

    private String product;

    private String licenseVersion;

    private Long generateTime;

    private String ext;
}
