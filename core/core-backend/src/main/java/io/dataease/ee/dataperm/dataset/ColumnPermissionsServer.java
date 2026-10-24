package io.dataease.ee.dataperm.dataset;

import io.dataease.api.permissions.dataset.api.ColumnPermissionsApi;
import io.dataease.api.permissions.dataset.dto.DataSetColumnPermissionsDTO;
import io.dataease.ee.dataperm.dao.entity.DatasetColumnPermission;
import io.dataease.result.PageResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据集列权限（/dataset/columnPermissions）。{@link #list} 供核心取数链路直接调用，不做管理权限校验。
 */
@RestController("columnPermissionsServer")
@RequestMapping("/dataset/columnPermissions")
public class ColumnPermissionsServer implements ColumnPermissionsApi {

    private final EeColumnPermissionManage columnManage;

    private final EeDatapermSupport support;

    public ColumnPermissionsServer(EeColumnPermissionManage columnManage, EeDatapermSupport support) {
        this.columnManage = columnManage;
        this.support = support;
    }

    @Override
    public PageResult<DataSetColumnPermissionsDTO> columnPermissions(Long datasetId, int goPage, int pageSize) {
        support.checkManage(datasetId);
        return columnManage.page(datasetId, goPage, pageSize);
    }

    @Override
    public void save(DataSetColumnPermissionsDTO request) {
        Long datasetId = request.getDatasetId();
        if (request.getId() != null) {
            datasetId = columnManage.require(request.getId()).getDatasetId();
        }
        support.checkManage(datasetId);
        columnManage.save(request, datasetId);
    }

    @Override
    public void delete(DataSetColumnPermissionsDTO request) {
        DatasetColumnPermission row = columnManage.require(request.getId());
        support.checkManage(row.getDatasetId());
        columnManage.delete(row.getId());
    }

    @Override
    public DataSetColumnPermissionsDTO DataSetColumnPermissionInfo(DataSetColumnPermissionsDTO request) {
        support.checkManage(request.getDatasetId());
        String type = StringUtils.isBlank(request.getAuthTargetType()) ? null
                : EeDatapermSupport.normalizeTargetType(request.getAuthTargetType(), false);
        return columnManage.info(request.getDatasetId(), type, request.getAuthTargetId());
    }

    @Override
    public List<DataSetColumnPermissionsDTO> list(DataSetColumnPermissionsDTO request) {
        return columnManage.list(request);
    }
}
