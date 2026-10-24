package io.dataease.ee.dataperm.dataset;

import io.dataease.api.permissions.auth.dto.PermissionSubjectRequest;
import io.dataease.api.permissions.auth.vo.SubjectNode;
import io.dataease.api.permissions.dataset.api.RowPermissionsApi;
import io.dataease.api.permissions.dataset.dto.DataSetRowPermissionsTreeDTO;
import io.dataease.api.permissions.dataset.dto.DatasetRowPermissionsTreeRequest;
import io.dataease.api.permissions.dataset.dto.Item;
import io.dataease.api.permissions.dataset.dto.WhiteListUsersRequest;
import io.dataease.api.permissions.user.vo.UserFormVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.dao.entity.DatasetRowPermission;
import io.dataease.result.PageResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据集行权限（/dataset/rowPermissions）。
 *
 * <p>{@link #getUserById}、{@link #list}、{@link #authObjs} 同时被核心取数链路（PermissionManage 等）直接调用，
 * 这些 Java 调用不做管理权限校验；HTTP 接口均要求对数据集有管理权限。</p>
 */
@RestController("rowPermissionsServer")
@RequestMapping("/dataset/rowPermissions")
public class RowPermissionsServer implements RowPermissionsApi {

    private final EeRowPermissionManage rowManage;

    private final EeColumnPermissionManage columnManage;

    private final EeDatapermSupport support;

    public RowPermissionsServer(EeRowPermissionManage rowManage, EeColumnPermissionManage columnManage, EeDatapermSupport support) {
        this.rowManage = rowManage;
        this.columnManage = columnManage;
        this.support = support;
    }

    @Override
    public PageResult<DataSetRowPermissionsTreeDTO> rowPermissions(Long datasetId, int goPage, int pageSize) {
        support.checkManage(datasetId);
        return rowManage.page(datasetId, goPage, pageSize);
    }

    @Override
    public void save(DataSetRowPermissionsTreeDTO request) {
        Long datasetId = request.getDatasetId();
        if (request.getId() != null) {
            // 更新时以已有规则的数据集为准，防止把规则挪到无权管理的数据集
            datasetId = rowManage.require(request.getId()).getDatasetId();
        }
        support.checkManage(datasetId);
        rowManage.save(request, datasetId);
    }

    @Override
    public void delete(DataSetRowPermissionsTreeDTO request) {
        DatasetRowPermission row = rowManage.require(request.getId());
        support.checkManage(row.getDatasetId());
        rowManage.delete(row.getId());
    }

    /**
     * 数据集所在组织中可作为授权对象的角色/用户。核心计算列权限时以此过滤用户角色
     */
    @Override
    public List<Item> authObjs(Long datasetId, String type) {
        if (EeDatapermSupport.isHttpCall("/rowPermissions/authObjs")) {
            support.checkManage(datasetId);
        }
        Long oid = datasetId == null ? null : support.datasetOrg(datasetId);
        if (oid != null) {
            return support.orgSubjects(oid, type);
        }
        // 数据集未登记组织时，退回为已配置规则的角色
        List<Item> result = new ArrayList<>();
        if (DatapermConstants.TARGET_ROLE.equalsIgnoreCase(type) && datasetId != null) {
            Set<Long> roleIds = new LinkedHashSet<>(rowManage.roleTargetsOf(datasetId));
            roleIds.addAll(columnManage.roleTargetsOf(datasetId));
            support.roleNames(roleIds).forEach((id, name) -> {
                Item item = new Item();
                item.setId(id);
                item.setName(name);
                result.add(item);
            });
        }
        return result;
    }

    @Override
    public DataSetRowPermissionsTreeDTO dataSetRowPermissionInfo(DataSetRowPermissionsTreeDTO request) {
        support.checkManage(request.getDatasetId());
        String type = StringUtils.isBlank(request.getAuthTargetType()) ? null
                : EeDatapermSupport.normalizeTargetType(request.getAuthTargetType(), true);
        return rowManage.info(request.getDatasetId(), type, request.getAuthTargetId());
    }

    /**
     * 白名单候选：角色规则为该角色的成员
     */
    @Override
    public List<UserFormVO> whiteListUsers(WhiteListUsersRequest request) {
        support.checkManage(request.getDatasetId());
        if (!DatapermConstants.TARGET_ROLE.equalsIgnoreCase(request.getAuthTargetType()) || request.getAuthTargetId() == null) {
            return new ArrayList<>();
        }
        Long oid = support.datasetOrg(request.getDatasetId());
        return support.roleMembers(request.getAuthTargetId()).stream()
                .filter(user -> !EeConstants.ADMIN_UID.equals(user.getId()))
                .filter(user -> oid == null || oid.equals(user.getDefaultOid()))
                .toList();
    }

    @Override
    public UserFormVO getUserById(Long id) {
        return support.userForm(id);
    }

    @Override
    public List<DataSetRowPermissionsTreeDTO> list(DatasetRowPermissionsTreeRequest request) {
        return rowManage.list(request);
    }

    @Override
    public List<SubjectNode> subjectTree(PermissionSubjectRequest request) {
        support.checkManage(request.getDatasetId());
        return support.subjectTree(support.datasetOrg(request.getDatasetId()), request.getType());
    }
}
