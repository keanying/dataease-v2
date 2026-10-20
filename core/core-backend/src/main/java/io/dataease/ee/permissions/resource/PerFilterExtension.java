package io.dataease.ee.permissions.resource;

import io.dataease.api.visualization.request.VisualizationWorkbranchQueryRequest;
import io.dataease.api.visualization.vo.VisualizationResourceVO;
import io.dataease.api.visualization.vo.VisualizationStoreVO;
import io.dataease.api.xpack.share.vo.XpackShareGridVO;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.interact.EeInvocation;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.EeResourceTypes;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.function.Function;

/**
 * 工作台最近使用、我的收藏、我的分享列表：按当前组织与权限过滤并写入权限值
 */
@EeExtension({"perFilterManage", "perFilterShareManage"})
public class PerFilterExtension {

    private final EePermissionManage permissionManage;

    public PerFilterExtension(EePermissionManage permissionManage) {
        this.permissionManage = permissionManage;
    }

    @SuppressWarnings("unchecked")
    public PageResult<?> query(EeInvocation invocation, int pageNum, int pageSize, VisualizationWorkbranchQueryRequest request) throws Throwable {
        PageResult<Object> result = (PageResult<Object>) invocation.proceed();
        if (result == null || CollectionUtils.isEmpty(result.getRecords())) {
            return result;
        }
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        Map<Integer, Map<Long, Perm>> cache = new HashMap<>();
        Function<Integer, Map<Long, Perm>> loader = rtId -> cache.computeIfAbsent(rtId, key -> permissionManage.effective(uid, oid, key));
        List<Object> kept = new ArrayList<>();
        for (Object record : result.getRecords()) {
            Long resourceId;
            String type;
            if (record instanceof VisualizationResourceVO vo) {
                resourceId = vo.getResourceId();
                type = vo.getType();
            } else if (record instanceof VisualizationStoreVO vo) {
                resourceId = vo.getResourceId();
                type = vo.getType();
            } else if (record instanceof XpackShareGridVO vo) {
                resourceId = vo.getResourceId();
                type = vo.getType();
            } else {
                kept.add(record);
                continue;
            }
            Integer rtId = EeResourceTypes.rtId(type);
            Perm perm = rtId == null ? null : loader.apply(rtId).get(resourceId);
            if (perm == null || perm.isNone()) {
                continue;
            }
            if (record instanceof VisualizationResourceVO vo) {
                vo.setWeight(perm.weight());
            } else if (record instanceof VisualizationStoreVO vo) {
                vo.setWeight(perm.weight());
            } else if (record instanceof XpackShareGridVO vo) {
                vo.setWeight(perm.weight());
            }
            kept.add(record);
        }
        long removed = result.getRecords().size() - kept.size();
        result.setRecords(kept);
        result.setTotal(Math.max(0, result.getTotal() - removed));
        return result;
    }
}
