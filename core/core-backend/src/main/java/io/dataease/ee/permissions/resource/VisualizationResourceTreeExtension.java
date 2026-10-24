package io.dataease.ee.permissions.resource;

import io.dataease.ee.threshold.ThresholdManage;
import io.dataease.utils.LogUtil;
import org.springframework.beans.factory.ObjectProvider;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.interact.EeInvocation;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 仪表板/数据大屏资源树
 */
@EeExtension("visualizationResourceTree")
public class VisualizationResourceTreeExtension {

    private final EeResourceTreeSupport support;

    private final ObjectProvider<ThresholdManage> thresholdManage;

    public VisualizationResourceTreeExtension(EeResourceTreeSupport support, ObjectProvider<ThresholdManage> thresholdManage) {
        this.support = support;
        this.thresholdManage = thresholdManage;
    }

    public static int rtId(String busiFlag) {
        return StringUtils.equalsAnyIgnoreCase(busiFlag, "dataV", "screen") ? EeConstants.RT_SCREEN : EeConstants.RT_PANEL;
    }

    @SuppressWarnings("unchecked")
    public List<BusiNodeVO> tree(EeInvocation invocation, BusiNodeRequest request) throws Throwable {
        List<BusiNodeVO> nodes = (List<BusiNodeVO>) invocation.proceed();
        return support.filter(nodes, rtId(request.getBusiFlag()));
    }

    public void innerSave(DataVisualizationInfo info, Long id) {
        support.register(rtId(info.getType()), id == null ? info.getId() : id, info.getName(), info.getPid(),
                !StringUtils.equalsIgnoreCase(info.getNodeType(), "folder"),
                Boolean.TRUE.equals(info.getMobileLayout()) ? 1 : 0, info.getStatus());
    }

    public void innerEdit(DataVisualizationInfo info) {
        if (Boolean.TRUE.equals(info.getOrgRoot())) {
            return;
        }
        support.update(rtId(info.getType()), info.getId(), info.getName(), info.getPid(),
                !StringUtils.equalsIgnoreCase(info.getNodeType(), "folder"), null, info.getStatus());
    }

    public void move(DataVisualizationBaseRequest request) {
        support.update(rtId(StringUtils.defaultIfBlank(request.getType(), request.getBusiFlag())), request.getId(),
                request.getName(), request.getPid(), !StringUtils.equalsIgnoreCase(request.getNodeType(), "folder"), null, null);
    }

    public void delete(Long id, boolean rootOrgNode) {
        if (!rootOrgNode) {
            support.remove(id);
        }
        // 删除仪表板/数据大屏（含子节点）后清理其阈值告警
        ThresholdManage manage = thresholdManage.getIfAvailable();
        if (manage != null) {
            try {
                manage.removeOrphans();
            } catch (Exception e) {
                LogUtil.error("remove thresholds of deleted visualization failed: " + e.getMessage(), e);
            }
        }
    }
}
