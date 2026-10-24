package io.dataease.ee.sync.server;

import io.dataease.api.sync.datasource.api.SyncDatasourceApi;
import io.dataease.api.sync.datasource.dto.DBTableDTO;
import io.dataease.api.sync.datasource.dto.DatasourceGridRequest;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.manage.SyncAccess;
import io.dataease.ee.sync.manage.SyncDatasourceManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.datasource.DatasourceDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.datasource.DatasourceRole;
import io.dataease.result.PageResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 同步管理：数据源
 */
@RestController("syncDatasourceServer")
@RequestMapping("/sync/datasource")
public class SyncDatasourceServer implements SyncDatasourceApi {

    private final SyncDatasourceManage manage;
    private final SyncAccess access;

    public SyncDatasourceServer(SyncDatasourceManage manage, SyncAccess access) {
        this.manage = manage;
        this.access = access;
    }

    /**
     * 数据源维护：同步数据源菜单
     */
    private Long manageScope() {
        access.check(SyncConstants.MENU_DATASOURCE);
        return access.oid();
    }

    /**
     * 数据源读取：数据源或任务菜单（任务表单需要选择数据源、读取表与字段）
     */
    private Long readScope() {
        access.check(SyncConstants.MENU_DATASOURCE, SyncConstants.MENU_TASK);
        return access.oid();
    }

    @Override
    public PageResult<DatasourceRequest> sourcePager(int goPage, int pageSize, DatasourceGridRequest request) {
        return manage.pager(manageScope(), DatasourceRole.SOURCE, goPage, pageSize, request);
    }

    @Override
    public PageResult<DatasourceRequest> targetPager(int goPage, int pageSize, DatasourceGridRequest request) {
        return manage.pager(manageScope(), DatasourceRole.TARGET, goPage, pageSize, request);
    }

    @Override
    public void save(DatasourceRequest datasourceRequest) throws DEException {
        manage.save(manageScope(), datasourceRequest);
    }

    @Override
    public Map<String, Object> update(DatasourceRequest datasourceRequest) throws DEException {
        return manage.update(manageScope(), datasourceRequest);
    }

    @Override
    public void delete(String datasourceId) throws DEException {
        manage.delete(manageScope(), datasourceId);
    }

    @Override
    public Object datasourceTypes() throws DEException {
        readScope();
        return manage.types();
    }

    @Override
    public String validate(DatasourceRequest datasourceRequest) throws DEException {
        manageScope();
        return manage.validate(datasourceRequest);
    }

    @Override
    public List<String> getSchema(DatasourceRequest datasourceRequest) throws DEException {
        manageScope();
        return manage.getSchema(datasourceRequest);
    }

    @Override
    public DatasourceDTO validate(String datasourceId) throws DEException {
        return manage.validate(readScope(), datasourceId);
    }

    @Override
    public List<String> latestUse(String sourceType) {
        return manage.latestUse(manageScope(), sourceType);
    }

    @Override
    public DatasourceDTO get(String datasourceId) throws DEException {
        return manage.get(manageScope(), datasourceId);
    }

    @Override
    public void batchDel(List<String> ids) throws DEException {
        manage.batchDelete(manageScope(), ids);
    }

    @Override
    public Map<String, Object> getFields(DatasourceRequest getDsRequest) throws DEException {
        return manage.fields(readScope(), getDsRequest);
    }

    @Override
    public List<DatasourceDTO> listByType(String type, Integer datasourceRole) throws DEException {
        return manage.listByType(readScope(), type, datasourceRole);
    }

    @Override
    public List<DBTableDTO> getTableList(String dsId) throws DEException {
        return manage.tables(readScope(), dsId);
    }

    @Override
    public String query2Root(Long id) {
        readScope();
        return null;
    }

    @Override
    public Long getLogResourceId(String id) {
        readScope();
        return SyncDatasourceManage.parseId(id);
    }
}
