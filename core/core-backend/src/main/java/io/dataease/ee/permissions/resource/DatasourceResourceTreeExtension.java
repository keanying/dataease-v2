package io.dataease.ee.permissions.resource;

import io.dataease.dao.auto.entity.CoreDatasource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.interact.EeInvocation;
import io.dataease.extensions.datasource.dto.DatasourceDTO;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 数据源资源树
 */
@EeExtension("datasourceResourceTree")
public class DatasourceResourceTreeExtension {

    private final EeResourceTreeSupport support;

    public DatasourceResourceTreeExtension(EeResourceTreeSupport support) {
        this.support = support;
    }

    @SuppressWarnings("unchecked")
    public List<BusiNodeVO> tree(EeInvocation invocation, BusiNodeRequest request) throws Throwable {
        List<BusiNodeVO> nodes = (List<BusiNodeVO>) invocation.proceed();
        return support.filter(nodes, EeConstants.RT_DATASOURCE);
    }

    public void innerSave(DatasourceDTO dto) {
        support.register(EeConstants.RT_DATASOURCE, dto.getId(), dto.getName(), dto.getPid(), !isFolder(dto.getType()), 0, 1);
    }

    public void innerEdit(CoreDatasource datasource) {
        support.update(EeConstants.RT_DATASOURCE, datasource.getId(), datasource.getName(), datasource.getPid(),
                !isFolder(datasource.getType()), null, null);
    }

    public void innerEditName(CoreDatasource datasource) {
        support.update(EeConstants.RT_DATASOURCE, datasource.getId(), datasource.getName(), datasource.getPid(),
                !isFolder(datasource.getType()), null, null);
    }

    public void move(DatasourceDTO dto) {
        support.update(EeConstants.RT_DATASOURCE, dto.getId(), dto.getName(), dto.getPid(), !isFolder(dto.getType()), null, null);
    }

    public void delete(Long id, boolean rootOrgNode) {
        if (!rootOrgNode) {
            support.remove(id);
        }
    }

    private boolean isFolder(String type) {
        return StringUtils.equalsIgnoreCase(type, "folder");
    }
}
