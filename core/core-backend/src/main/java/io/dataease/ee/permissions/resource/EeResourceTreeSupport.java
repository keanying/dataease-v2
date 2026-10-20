package io.dataease.ee.permissions.resource;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EeGrantManage;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.model.BusiNodeVO;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 各类资源树扩展的公共逻辑：按当前组织与权限裁剪资源树、登记资源。
 */
@Component
public class EeResourceTreeSupport {

    private final EePermissionManage permissionManage;

    private final EeBusiResourceManage busiResourceManage;

    private final EeGrantManage grantManage;

    public EeResourceTreeSupport(EePermissionManage permissionManage, EeBusiResourceManage busiResourceManage, EeGrantManage grantManage) {
        this.permissionManage = permissionManage;
        this.busiResourceManage = busiResourceManage;
        this.grantManage = grantManage;
    }

    /**
     * 裁剪资源树：只保留当前组织内有权限的节点及其祖先目录，并写入权限值
     */
    public List<BusiNodeVO> filter(List<BusiNodeVO> roots, int rtId) {
        if (CollectionUtils.isEmpty(roots)) {
            return roots;
        }
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        if (uid == null || oid == null) {
            return new ArrayList<>();
        }
        Set<Long> orgResources = busiResourceManage.idsByOrg(rtId, oid);
        Map<Long, Perm> perms = permissionManage.effective(uid, oid, rtId);
        int rootWeight = permissionManage.canManageRoot(uid, oid, rtId) ? EeConstants.WEIGHT_MANAGE : EeConstants.WEIGHT_READ;
        List<BusiNodeVO> result = new ArrayList<>();
        for (BusiNodeVO node : roots) {
            BusiNodeVO kept = filterNode(node, orgResources, perms, rootWeight);
            if (kept != null) {
                result.add(kept);
            }
        }
        return result;
    }

    private BusiNodeVO filterNode(BusiNodeVO node, Set<Long> orgResources, Map<Long, Perm> perms, int rootWeight) {
        boolean hadChildrenList = node.getChildren() != null;
        List<BusiNodeVO> children = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(node.getChildren())) {
            for (BusiNodeVO child : node.getChildren()) {
                BusiNodeVO kept = filterNode(child, orgResources, perms, rootWeight);
                if (kept != null) {
                    children.add(kept);
                }
            }
        }
        Long id = node.getId();
        if (id != null && id == 0L) {
            node.setWeight(rootWeight);
            node.setExt(0);
            node.setChildren(children.isEmpty() && !hadChildrenList ? null : children);
            return node;
        }
        if (id == null || !orgResources.contains(id)) {
            return null;
        }
        Perm perm = perms.get(id);
        if ((perm == null || perm.isNone()) && children.isEmpty()) {
            return null;
        }
        if (perm == null || perm.isNone()) {
            node.setWeight(EeConstants.WEIGHT_READ);
            node.setExt(0);
        } else {
            node.setWeight(perm.weight());
            node.setExt(perm.displayExt());
        }
        node.setChildren(children.isEmpty() && !hadChildrenList ? null : children);
        return node;
    }

    /**
     * 新建资源后登记，并为创建者授权
     */
    @Transactional
    public void register(int rtId, Long id, String name, Long pid, boolean leaf, Integer extraFlag, Integer extraFlag1) {
        if (id == null) {
            return;
        }
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            oid = EeConstants.ROOT_ORG_ID;
        }
        boolean created = busiResourceManage.save(rtId, id, name, pid, leaf, oid, extraFlag, extraFlag1);
        if (created) {
            Long uid = V3UserUtil.getUid();
            grantManage.onResourceCreated(rtId, id, pid, uid, oid, uid == null || permissionManage.isOrgAdmin(uid, oid));
        }
    }

    @Transactional
    public void update(int rtId, Long id, String name, Long pid, boolean leaf, Integer extraFlag, Integer extraFlag1) {
        if (id == null) {
            return;
        }
        if (!busiResourceManage.registered(id)) {
            register(rtId, id, name, pid, leaf, extraFlag, extraFlag1);
            return;
        }
        busiResourceManage.save(rtId, id, name, pid, leaf, null, extraFlag, extraFlag1);
    }

    @Transactional
    public void remove(Long id) {
        List<Long> removed = busiResourceManage.deleteWithChildren(id);
        grantManage.deleteByResources(removed);
    }
}
