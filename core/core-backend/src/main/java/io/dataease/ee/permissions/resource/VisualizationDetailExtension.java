package io.dataease.ee.permissions.resource;

import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.api.visualization.vo.DataVisualizationVO;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.lang3.StringUtils;

/**
 * 仪表板/数据大屏详情：按当前身份的实际权限写入 weight/ext（核心固定返回 9）
 */
@EeExtension("dataVisualizationServer")
public class VisualizationDetailExtension {

    private final EePermissionManage permissionManage;

    public VisualizationDetailExtension(EePermissionManage permissionManage) {
        this.permissionManage = permissionManage;
    }

    public DataVisualizationVO findById(DataVisualizationBaseRequest request, DataVisualizationVO result) {
        if (result == null || result.getId() == null || V3UserUtil.getUid() == null) {
            return result;
        }
        String type = StringUtils.defaultIfBlank(result.getType(), request.getBusiFlag());
        Perm perm = permissionManage.current(VisualizationResourceTreeExtension.rtId(type), result.getId());
        if (!perm.isNone()) {
            result.setWeight(perm.weight());
            result.setExt(perm.displayExt());
        }
        return result;
    }
}
