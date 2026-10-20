package io.dataease.ee.permissions.resource;

import io.dataease.dataset.manage.DatasetGroupManage;
import io.dataease.datasource.server.DatasourceServer;
import io.dataease.ee.common.EeConstants;
import io.dataease.visualization.manage.CoreVisualizationManage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 核心资源（仪表板、数据大屏、数据集、数据源）的删除器
 */
@Configuration
public class CoreResourceDeleters {

    @Bean
    public EeResourceDeleter panelDeleter(@Lazy CoreVisualizationManage manage) {
        return deleter(EeConstants.RT_PANEL, id -> manage.delete(id, false));
    }

    @Bean
    public EeResourceDeleter screenDeleter(@Lazy CoreVisualizationManage manage) {
        return deleter(EeConstants.RT_SCREEN, id -> manage.delete(id, false));
    }

    @Bean
    public EeResourceDeleter datasetDeleter(@Lazy DatasetGroupManage manage) {
        return deleter(EeConstants.RT_DATASET, id -> manage.delete(id, false));
    }

    @Bean
    public EeResourceDeleter datasourceDeleter(@Lazy DatasourceServer server) {
        return deleter(EeConstants.RT_DATASOURCE, id -> server.delete(id, false));
    }

    private static EeResourceDeleter deleter(int rtId, java.util.function.Consumer<Long> action) {
        return new EeResourceDeleter() {
            @Override
            public int rtId() {
                return rtId;
            }

            @Override
            public void delete(Long id) {
                action.accept(id);
            }
        };
    }
}
