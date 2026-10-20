package io.dataease.permission.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统管理员代理访问组织的上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class V3ProxyInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = -7722581283590185372L;

    private Long proxyOid;

    public boolean isProxy() {
        return proxyOid != null;
    }
}
