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
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.function.Function;

/**
 * 工作台最近使用、我的收藏、我的分享列表：按当前组织与权限过滤并写入权限值
 */
@EeExtension({"perFilterManage", "perFilterShareManage"})
public class PerFilterExtension {

    private final EePermissionManage permissionManage;

    private final ObjectProvider<EeWorkbenchContributor> contributors;

    public PerFilterExtension(EePermissionManage permissionManage, ObjectProvider<EeWorkbenchContributor> contributors) {
        this.permissionManage = permissionManage;
        this.contributors = contributors;
    }

    @SuppressWarnings("unchecked")
    public PageResult<?> query(EeInvocation invocation, int pageNum, int pageSize, VisualizationWorkbranchQueryRequest request) throws Throwable {
        PageResult<Object> result = (PageResult<Object>) invocation.proceed();
        result = mergeContributions(result, pageNum, pageSize, request);
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

    /**
     * 最近使用 / 我的收藏（核心固定查询第一页）：合并企业版资源后按最后编辑时间重新排序并截取一页
     */
    private PageResult<Object> mergeContributions(PageResult<Object> result, int pageNum, int pageSize, VisualizationWorkbranchQueryRequest request) {
        if (request == null || pageNum != 1 || pageSize <= 0) {
            return result;
        }
        boolean recent = "recent".equals(request.getQueryFrom());
        boolean store = "store".equals(request.getQueryFrom());
        if (!recent && !store) {
            return result;
        }
        List<Object> extra = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> {
            if (recent) {
                extra.addAll(contributor.recent(request, pageSize));
            } else {
                extra.addAll(contributor.store(request, pageSize));
            }
        });
        if (extra.isEmpty()) {
            return result;
        }
        PageResult<Object> merged = result == null ? new PageResult<>() : result;
        List<Object> all = new ArrayList<>(merged.getRecords() == null ? Collections.emptyList() : merged.getRecords());
        all.addAll(extra);
        Comparator<Object> byTime = Comparator.comparing(PerFilterExtension::editTime);
        all.sort(request.isAsc() ? byTime : byTime.reversed());
        merged.setRecords(new ArrayList<>(all.subList(0, Math.min(pageSize, all.size()))));
        merged.setTotal(merged.getTotal() + extra.size());
        merged.setCurrent(1);
        merged.setSize(pageSize);
        return merged;
    }

    private static long editTime(Object record) {
        Long time = null;
        if (record instanceof VisualizationResourceVO vo) {
            time = vo.getLastEditTime();
        } else if (record instanceof VisualizationStoreVO vo) {
            time = vo.getLastEditTime();
        }
        return time == null ? 0L : time;
    }
}
