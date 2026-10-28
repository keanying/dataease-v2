package io.dataease.ee.datasource.dm;

import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.vo.DatasourceConfiguration;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

/**
 * 达梦（DM8）数据源连接配置：主机、端口、用户名、密码、模式（schema）与额外参数。
 *
 * <p>JDBC URL 为 {@code jdbc:dm://host:port?<额外参数>}；达梦一个实例只有一个数据库，因此不使用“数据库”一项。
 * 模式在连接后以 {@code SET SCHEMA "<模式>"} 切换（URL 的 schema 参数会被驱动转成大写，不支持小写模式名）。
 * 也可直接填写以 {@code jdbc:dm:} 开头的 JDBC 连接串。</p>
 */
@Getter
@Setter
public class DmConfiguration extends DatasourceConfiguration {

    public static final String DRIVER = "dm.jdbc.driver.DmDriver";

    private String driver = DRIVER;

    private String extraParams = "";

    @Override
    public String getJdbc() {
        if (StringUtils.isNotEmpty(getUrlType()) && !getUrlType().equalsIgnoreCase("hostName")) {
            if (!StringUtils.startsWith(StringUtils.trim(getJdbcUrl()), "jdbc:dm:")) {
                DEException.throwException("Illegal jdbcUrl: " + getJdbcUrl());
            }
            return getJdbcUrl().trim();
        }
        if (StringUtils.isBlank(getLHost()) || getLPort() == null) {
            DEException.throwException("host and port are required");
        }
        StringBuilder url = new StringBuilder("jdbc:dm://").append(getLHost().trim()).append(":").append(getLPort());
        StringBuilder query = new StringBuilder();
        String extra = StringUtils.trimToEmpty(extraParams);
        if (StringUtils.startsWith(extra, "?") || StringUtils.startsWith(extra, "&")) {
            extra = extra.substring(1);
        }
        if (!extra.isEmpty()) {
            if (!query.isEmpty()) {
                query.append("&");
            }
            query.append(extra);
        }
        if (!query.isEmpty()) {
            url.append("?").append(query);
        }
        return url.toString();
    }
}
