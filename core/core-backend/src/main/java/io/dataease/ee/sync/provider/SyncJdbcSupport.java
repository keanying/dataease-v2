package io.dataease.ee.sync.provider;

import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.provider.ExtendedJdbcClassLoader;
import io.dataease.extensions.sync.utils.JdbcDriverLoader;
import io.dataease.i18n.Translator;
import io.dataease.utils.CommonBeanFactory;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置同步数据源的 JDBC 驱动加载与连接工具。
 *
 * <p>优先从 {@code dataease.path.driver} 目录（与核心数据源一致）加载驱动，目录不存在或驱动缺失时回退到应用类路径
 * （社区版自带 MariaDB 驱动，可连接 MySQL / MariaDB / Doris）。</p>
 */
public final class SyncJdbcSupport {

    private static final String DEFAULT_DRIVER_PATH = "/opt/dataease3.0/drivers";

    private static final Map<String, Driver> DRIVERS = new ConcurrentHashMap<>();

    private SyncJdbcSupport() {
    }

    public static Connection connect(String driverClass, String url, String username, String password) {
        if (StringUtils.isBlank(url)) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        Driver driver = loadDriver(driverClass);
        Properties props = new Properties();
        if (StringUtils.isNotBlank(username)) {
            props.setProperty("user", username);
        }
        if (StringUtils.isNotBlank(password)) {
            props.setProperty("password", password);
        }
        Connection connection = null;
        try {
            connection = driver.connect(url, props);
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_sync_connect_failed", rootMessage(e)));
        }
        if (connection == null) {
            DEException.throwException(Translator.get("ee_sync_connect_failed", url));
        }
        return connection;
    }

    private static Driver loadDriver(String driverClass) {
        return DRIVERS.computeIfAbsent(driverClass, SyncJdbcSupport::instantiate);
    }

    private static Driver instantiate(String driverClass) {
        ClassLoader fallback = Thread.currentThread().getContextClassLoader();
        String dir = driverDirectory();
        if (new File(dir).isDirectory()) {
            try {
                ExtendedJdbcClassLoader loader = JdbcDriverLoader.getOrCreate(dir);
                return (Driver) loader.loadClass(driverClass).getDeclaredConstructor().newInstance();
            } catch (Throwable e) {
                LogUtil.debug("sync driver not found in " + dir + ": " + driverClass);
            }
        }
        try {
            return (Driver) Class.forName(driverClass, true, fallback == null ? SyncJdbcSupport.class.getClassLoader() : fallback)
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            DEException.throwException(Translator.get("ee_sync_driver_missing", driverClass));
        }
        return null;
    }

    private static String driverDirectory() {
        try {
            if (CommonBeanFactory.getApplicationContext() != null) {
                return CommonBeanFactory.getApplicationContext().getEnvironment()
                        .getProperty("dataease.path.driver", DEFAULT_DRIVER_PATH);
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_DRIVER_PATH;
    }

    public static String rootMessage(Throwable e) {
        Throwable cur = e;
        String msg = e.getMessage();
        while (cur != null) {
            if (StringUtils.isNotBlank(cur.getMessage())) {
                msg = cur.getMessage();
            }
            if (cur.getCause() == null || cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return msg == null ? e.getClass().getSimpleName() : msg;
    }
}
