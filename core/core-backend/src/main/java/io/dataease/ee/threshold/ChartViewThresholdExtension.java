package io.dataease.ee.threshold;

import io.dataease.ee.interact.EeExtension;
import io.dataease.utils.LogUtil;

import java.util.List;

/**
 * 核心 {@code ChartViewManege} 中阈值告警镜像钩子（{@code @XpackInteract("chartViewManage")}）的实现。
 */
@EeExtension("chartViewManage")
public class ChartViewThresholdExtension {

    private final ThresholdManage thresholdManage;

    public ChartViewThresholdExtension(ThresholdManage thresholdManage) {
        this.thresholdManage = thresholdManage;
    }

    /**
     * 图表停用：删除其告警
     */
    public void disuse(List<Long> chartIdList) {
        thresholdManage.disuse(chartIdList);
    }

    /**
     * 仪表板发布：清理已移除图表的告警并刷新检测任务
     */
    public void publishThreshold(Long resourceId, List<Long> chartIdList) {
        try {
            thresholdManage.publish(resourceId, chartIdList);
        } catch (Exception e) {
            LogUtil.error("publish threshold failed: " + e.getMessage(), e);
        }
    }

    /**
     * 清理某张表中仪表板的告警（发布/恢复镜像前）
     */
    public void removeThreshold(Long resourceId, String resourceTable) {
        try {
            thresholdManage.removeByResource(resourceId, resourceTable);
        } catch (Exception e) {
            LogUtil.error("remove threshold failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从另一张表恢复仪表板的告警
     */
    public void restoreThreshold(Long resourceId, String resourceTable) {
        try {
            thresholdManage.restoreByResource(resourceId, resourceTable);
        } catch (Exception e) {
            LogUtil.error("restore threshold failed: " + e.getMessage(), e);
        }
    }
}
