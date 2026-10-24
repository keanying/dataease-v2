package io.dataease.ee.permissions.resource;

import io.dataease.api.visualization.request.VisualizationWorkbranchQueryRequest;
import io.dataease.api.visualization.vo.VisualizationResourceVO;
import io.dataease.api.visualization.vo.VisualizationStoreVO;

import java.util.List;

/**
 * 为工作台“最近使用 / 我的收藏”补充核心查询未覆盖的企业版资源（如电子表格）。
 * 返回的记录随后由 {@link PerFilterExtension} 统一按权限过滤。
 */
public interface EeWorkbenchContributor {

    /**
     * 当前用户最近使用的资源，按最后编辑时间排序，最多 limit 条
     */
    List<VisualizationResourceVO> recent(VisualizationWorkbranchQueryRequest request, int limit);

    /**
     * 当前用户收藏的资源，按最后编辑时间排序，最多 limit 条
     */
    List<VisualizationStoreVO> store(VisualizationWorkbranchQueryRequest request, int limit);
}
