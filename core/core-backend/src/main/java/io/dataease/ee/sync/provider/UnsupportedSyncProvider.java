package io.dataease.ee.sync.provider;

import io.dataease.exception.DEException;
import io.dataease.extensions.sync.datatype.StandardDataType;
import io.dataease.extensions.sync.model.TableDTO;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.ConnectionObj;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.TaskInfoVO;
import io.dataease.extensions.sync.provider.SourceProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;

import java.util.List;

/**
 * 当前版本尚未实现的内置类型（如 Elasticsearch 源端），统一给出明确提示
 */
public class UnsupportedSyncProvider extends SyncProvider implements SourceProvider {

    private final String type;

    public UnsupportedSyncProvider(String type) {
        this.type = type;
    }

    private <T> T fail() {
        DEException.throwException(Translator.get("ee_sync_type_unsupported", type));
        return null;
    }

    @Override
    public String getProviderName() {
        return type;
    }

    @Override
    public List<String> getSchema(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public ConnectionObj getConnection(DatasourceConfiguration configuration) {
        return fail();
    }

    @Override
    public String checkStatus(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public String validate(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public void checkConfiguration(DatasourceRequest datasource) {
        fail();
    }

    @Override
    public List<TableDTO> getTables(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public List<TableFieldDTO> fetchTableField(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public List<TableFieldDTO> getFieldsBySql(DatasourceRequest datasourceRequest) {
        return fail();
    }

    @Override
    public String generatorSourceConfig(TaskInfoVO taskInfoVO) {
        return fail();
    }

    @Override
    public String appendIncrementConditions(TaskInfoVO taskInfo, String jobParameter) {
        return fail();
    }

    @Override
    public StandardDataType dataTypeToStandardDataType(String sourceType) {
        return StandardDataType.UNKNOWN;
    }
}
