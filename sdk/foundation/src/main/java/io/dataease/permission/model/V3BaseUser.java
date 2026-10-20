package io.dataease.permission.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前访问身份的基础信息。
 *
 * <p>{@link #oid} 为本次请求生效的组织：普通用户为 token 中的当前组织，
 * 系统管理员代理访问其他组织时为被代理组织。</p>
 */
@Data
public class V3BaseUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 3270893468210837312L;

    private Long id;

    private String account;

    private String name;

    private String email;

    private String phone;

    /**
     * 本次请求生效的组织 ID
     */
    private Long oid;

    /**
     * 用户默认组织 ID
     */
    private Long defaultOid;

    /**
     * 密码摘要，仅用于派生 token 签名密钥，不得输出到接口响应
     */
    private String pwd;

    private Boolean enable;

    private Integer origin;

    private String language;
}
