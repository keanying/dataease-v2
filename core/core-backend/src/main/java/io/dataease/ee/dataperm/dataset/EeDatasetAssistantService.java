package io.dataease.ee.dataperm.dataset;

import io.dataease.api.permissions.dataset.api.DatasetAssistantEnterpriseService;
import io.dataease.dataset.dao.ext.mapper.DataSetAssistantMapper;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQLBot 助手的企业版数据范围：当前组织内当前用户可查看的数据集（及其数据源、字段），以及用户角色。
 */
@Component
public class EeDatasetAssistantService implements DatasetAssistantEnterpriseService {

    private final DataSetAssistantMapper assistantMapper;

    private final EePermissionManage permissionManage;

    private final EeRoleManage roleManage;

    public EeDatasetAssistantService(@Lazy DataSetAssistantMapper assistantMapper, EePermissionManage permissionManage, EeRoleManage roleManage) {
        this.assistantMapper = assistantMapper;
        this.permissionManage = permissionManage;
        this.roleManage = roleManage;
    }

    @Override
    public List<Map<String, Object>> queryEnterprise(Long dsId, Long datasetId) {
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        if (uid == null || oid == null) {
            return new ArrayList<>();
        }
        Set<Long> readable = permissionManage.effective(uid, oid, EeConstants.RT_DATASET).entrySet().stream()
                .filter(entry -> entry.getValue().satisfies(EeConstants.WEIGHT_READ))
                .map(Map.Entry::getKey).collect(HashSet::new, Set::add, Set::addAll);
        if (readable.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : assistantMapper.queryAll(dsId, datasetId)) {
            Object id = row.get("cdg_id");
            if (id != null && readable.contains(Long.valueOf(id.toString()))) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 当前用户在当前组织内的角色。代理管理员只返回被代理组织的内置管理员角色；系统管理员本人不受行列权限限制，返回空
     */
    @Override
    public List<Map<String, Object>> queryUserRoles() {
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        List<Map<String, Object>> result = new ArrayList<>();
        if (uid == null || oid == null) {
            return result;
        }
        List<PerRole> roles;
        if (V3UserUtil.getProxy().isProxy()) {
            PerRole admin = roleManage.builtinRole(oid, EeConstants.ROLE_TYPE_ADMIN);
            roles = admin == null ? List.of() : List.of(admin);
        } else if (permissionManage.isSysAdmin(uid)) {
            roles = List.of();
        } else {
            roles = permissionManage.roles(uid, oid);
        }
        for (PerRole role : roles) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", role.getId());
            item.put("readonly", Boolean.TRUE.equals(role.getReadonly()));
            item.put("pid", role.getOid());
            result.add(item);
        }
        return result;
    }
}
