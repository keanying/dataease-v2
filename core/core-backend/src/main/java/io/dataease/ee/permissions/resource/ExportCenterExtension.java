package io.dataease.ee.permissions.resource;

import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.model.ExportTaskDTO;

/**
 * 导出中心：补充导出来源资源所属组织名称
 */
@EeExtension("exportCenter")
public class ExportCenterExtension {

    private final EeBusiResourceManage busiResourceManage;

    private final EeOrgManage orgManage;

    public ExportCenterExtension(EeBusiResourceManage busiResourceManage, EeOrgManage orgManage) {
        this.busiResourceManage = busiResourceManage;
        this.orgManage = orgManage;
    }

    public void setOrg(ExportTaskDTO task) {
        if (task == null || task.getExportFrom() == null) {
            return;
        }
        PerBusiResource resource = busiResourceManage.get(task.getExportFrom());
        if (resource == null) {
            return;
        }
        PerOrg org = orgManage.get(resource.getOrgId());
        if (org != null) {
            task.setOrgName(org.getName());
        }
    }
}
