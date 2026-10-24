package io.dataease.ee.dataperm.dataset;

import io.dataease.api.permissions.dataset.api.RowColPermissionApi;
import io.dataease.api.permissions.dataset.vo.ColPermissionInfo;
import io.dataease.api.permissions.dataset.vo.RowColPermissionItem;
import io.dataease.ee.dataperm.dao.entity.DatasetColumnPermission;
import io.dataease.ee.dataperm.dao.entity.DatasetRowPermission;
import io.dataease.extensions.view.dto.ColumnPermissionItem;
import io.dataease.extensions.view.dto.ColumnPermissions;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按数据集批量导出行列权限规则（type：row / col）
 */
@Component
public class EeRowColPermissionService implements RowColPermissionApi {

    public static final String TYPE_ROW = "row";

    public static final String TYPE_COL = "col";

    private final EeRowPermissionManage rowManage;

    private final EeColumnPermissionManage columnManage;

    public EeRowColPermissionService(EeRowPermissionManage rowManage, EeColumnPermissionManage columnManage) {
        this.rowManage = rowManage;
        this.columnManage = columnManage;
    }

    @Override
    public List<RowColPermissionItem> query(List<Long> datasetIds) {
        List<RowColPermissionItem> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(datasetIds)) {
            return result;
        }
        List<Long> ids = datasetIds.stream().filter(Objects::nonNull).distinct().toList();
        for (DatasetRowPermission row : rowManage.listByDatasets(ids)) {
            RowColPermissionItem item = new RowColPermissionItem();
            item.setId(row.getId());
            item.setType(TYPE_ROW);
            item.setEnable(Boolean.TRUE.equals(row.getEnable()));
            item.setDatasetId(row.getDatasetId());
            item.setAuthTargetType(row.getAuthTargetType());
            item.setAuthTargetId(row.getAuthTargetId() == null ? null : row.getAuthTargetId().toString());
            item.setPermissionText(row.getExpressionTree());
            item.setWhiteListUserIds(EeDatapermSupport.parseIdList(row.getWhiteListUser()));
            result.add(item);
        }
        for (DatasetColumnPermission column : columnManage.listByDatasets(ids)) {
            RowColPermissionItem item = new RowColPermissionItem();
            item.setId(column.getId());
            item.setType(TYPE_COL);
            item.setDatasetId(column.getDatasetId());
            item.setAuthTargetType(column.getAuthTargetType());
            item.setAuthTargetId(column.getAuthTargetId() == null ? null : column.getAuthTargetId().toString());
            item.setPermissionText(column.getPermissions());
            item.setWhiteListUserIds(EeDatapermSupport.parseIdList(column.getWhiteListUser()));
            ColumnPermissions permissions = EeColumnPermissionManage.parse(column.getPermissions());
            item.setEnable(permissions != null && Boolean.TRUE.equals(permissions.getEnable()));
            List<ColPermissionInfo> infos = new ArrayList<>();
            if (permissions != null && permissions.getColumns() != null) {
                for (ColumnPermissionItem columnItem : permissions.getColumns()) {
                    ColPermissionInfo info = new ColPermissionInfo();
                    info.setId(columnItem.getId());
                    info.setSelected(Boolean.TRUE.equals(columnItem.getSelected()));
                    info.setOpt(columnItem.getOpt());
                    info.setDesensitizationRule(columnItem.getDesensitizationRule());
                    infos.add(info);
                }
            }
            item.setColPermissionInfos(infos);
            result.add(item);
        }
        return result;
    }
}
