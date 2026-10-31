package io.dataease.config;

import org.hibernate.cfg.AvailableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * 在 Hibernate 启动前探测 MySQL 的 lower_case_table_names。
 *
 * <p>值为 1（Windows 上的 MySQL 默认值）时，库中表名以小写存储，而 QRTZ_* 表的物理名是大写；
 * 由于关闭了 JDBC 元数据读取（temp.use_jdbc_metadata_defaults=false），ddl-auto=update 按原样匹配表名，
 * 找不到已有的 Quartz 表，每次启动都重复 create table / add constraint，
 * 报 "Duplicate foreign key constraint name"。这里改为让 QRTZ_* 输出小写表名。</p>
 *
 * <p>只在配置的命名策略是 {@link DynamicCaseNamingStrategy} 且数据库为 MySQL 系时生效，其它情况不做任何改动。</p>
 */
@Component
public class QuartzTableCaseCustomizer implements HibernatePropertiesCustomizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuartzTableCaseCustomizer.class);

    private final DataSource dataSource;

    public QuartzTableCaseCustomizer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        Object strategy = hibernateProperties.get(AvailableSettings.PHYSICAL_NAMING_STRATEGY);
        String strategyName = strategy instanceof Class<?> clazz ? clazz.getName()
                : strategy == null ? null : strategy.toString().trim();
        if (!DynamicCaseNamingStrategy.class.getName().equals(strategyName)) {
            return;
        }
        if (lowerCaseTableNames()) {
            LOGGER.info("MySQL lower_case_table_names=1, Quartz tables are mapped with lower-case names");
            hibernateProperties.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, new DynamicCaseNamingStrategy(true));
        }
    }

    private boolean lowerCaseTableNames() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !(product.toLowerCase().contains("mysql") || product.toLowerCase().contains("mariadb"))) {
                return false;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT @@lower_case_table_names")) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to detect lower_case_table_names, keep upper-case Quartz table names: {}", e.getMessage());
            return false;
        }
    }
}
