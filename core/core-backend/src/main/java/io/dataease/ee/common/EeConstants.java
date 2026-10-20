package io.dataease.ee.common;

/**
 * 企业版公共常量
 */
public final class EeConstants {

    private EeConstants() {
    }

    /**
     * 系统管理员用户ID
     */
    public static final Long ADMIN_UID = 1L;

    /**
     * 根组织（默认组织）ID
     */
    public static final Long ROOT_ORG_ID = 1L;

    /**
     * 根组织内置管理员角色ID
     */
    public static final Long ROOT_ADMIN_ROLE_ID = 1L;

    public static final int SUBJECT_USER = 0;

    public static final int SUBJECT_ROLE = 1;

    /**
     * 角色类型
     */
    public static final int ROLE_TYPE_STAFF = 0;

    public static final int ROLE_TYPE_ANALYST = 7;

    public static final int ROLE_TYPE_ADMIN = 9;

    /**
     * 资源类型（与 BusiResourceEnum.flag 一致）
     */
    public static final int RT_MENU = 0;

    public static final int RT_PANEL = 1;

    public static final int RT_SCREEN = 2;

    public static final int RT_DATASET = 3;

    public static final int RT_DATASOURCE = 4;

    public static final int RT_DATA_FILLING = 8;

    public static final int RT_SPREADSHEET = 10;

    /**
     * 权限等级
     */
    public static final int WEIGHT_READ = 1;

    public static final int WEIGHT_USE = 2;

    public static final int WEIGHT_EXPORT = 4;

    public static final int WEIGHT_MANAGE = 7;

    public static final int WEIGHT_AUTH = 9;

    /**
     * 全部导出权限位（资源导出、数据导出、明细导出）
     */
    public static final int EXT_ALL = 111;

    /**
     * 用户来源
     */
    public static final int ORIGIN_LOCAL = 0;

    public static final int ORIGIN_LDAP = 1;

    public static final int ORIGIN_OIDC = 2;

    public static final int ORIGIN_CAS = 3;

    public static final int ORIGIN_LARK = 4;

    public static final int ORIGIN_DINGTALK = 5;

    public static final int ORIGIN_WECOM = 6;

    public static final int ORIGIN_LARKSUITE = 7;

    public static final int ORIGIN_OAUTH2 = 9;

    public static final int ORIGIN_SAML = 10;

    public static final String DEFAULT_PWD = "DataEase@123456";

    public static final String ADMIN_PROXY_HEADER = "X-DE-ADMIN-PROXY";

    public static final String GATEWAY_FLAG_HEADER = "DE-GATEWAY-FLAG";
}
