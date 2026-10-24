package io.dataease.ee.datafilling.manage;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.resource.EeResourceDeleter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 删除组织时同步删除数据填报资源
 */
@Configuration
public class DfResourceDeleterConfig {

    @Bean
    public EeResourceDeleter dataFillingDeleter(@Lazy DfFormManage formManage) {
        return new EeResourceDeleter() {
            @Override
            public int rtId() {
                return EeConstants.RT_DATA_FILLING;
            }

            @Override
            public void delete(Long id) {
                formManage.forceDelete(id);
            }
        };
    }
}
