package io.dataease.ee.permissions.resource;

import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.interact.EeInvocation;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 数据集资源树
 */
@EeExtension("authResourceTree")
public class DatasetResourceTreeExtension {

    private final EeResourceTreeSupport support;

    public DatasetResourceTreeExtension(EeResourceTreeSupport support) {
        this.support = support;
    }

    @SuppressWarnings("unchecked")
    public List<BusiNodeVO> tree(EeInvocation invocation, BusiNodeRequest request) throws Throwable {
        List<BusiNodeVO> nodes = (List<BusiNodeVO>) invocation.proceed();
        return support.filter(nodes, EeConstants.RT_DATASET);
    }

    public void innerSave(DatasetGroupInfoDTO dto) {
        support.register(EeConstants.RT_DATASET, dto.getId(), dto.getName(), dto.getPid(), !isFolder(dto), 0, 1);
    }

    public void innerEdit(DatasetGroupInfoDTO dto) {
        if (Boolean.TRUE.equals(dto.getOrgRoot())) {
            return;
        }
        support.update(EeConstants.RT_DATASET, dto.getId(), dto.getName(), dto.getPid(), !isFolder(dto), null, null);
    }

    public void move(DatasetGroupInfoDTO dto) {
        support.update(EeConstants.RT_DATASET, dto.getId(), dto.getName(), dto.getPid(), !isFolder(dto), null, null);
    }

    public void delete(Long id, boolean rootOrgNode) {
        if (!rootOrgNode) {
            support.remove(id);
        }
    }

    private boolean isFolder(DatasetGroupInfoDTO dto) {
        return StringUtils.equalsIgnoreCase(dto.getNodeType(), "folder");
    }
}
