package io.dataease.plugins.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 插件描述信息，来源于插件包内的 {@code plugin/extensions*.json}。
 */
@Data
public class DataEasePluginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = -2410577658476576935L;

    private String id;

    private String name;

    private String icon;

    private String flag;

    private String version;

    private String requireVersion;

    private String developer;

    /**
     * 插件类型相关配置，JSON 字符串
     */
    private String config;

    private String moduleName;
}
