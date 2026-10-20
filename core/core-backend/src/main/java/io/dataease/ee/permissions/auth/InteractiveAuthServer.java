package io.dataease.ee.permissions.auth;

import io.dataease.api.permissions.auth.api.InteractiveAuthApi;
import io.dataease.api.permissions.auth.dto.*;
import io.dataease.api.permissions.auth.vo.PermissionValVO;
import io.dataease.api.permissions.auth.vo.ResourceNodeVO;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.ee.permissions.resource.EeResourceTreeSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import io.dataease.model.ExportTaskDTO;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 内部资源交互：供其他模块登记资源、校验和查询权限
 */
@RestController("interactiveAuthServer")
@RequestMapping("/interactive")
public class InteractiveAuthServer implements InteractiveAuthApi {

    private final EeMenuManage menuManage;

    private final EePermissionManage permissionManage;

    private final EeBusiResourceManage busiResourceManage;

    private final EeResourceTreeSupport treeSupport;

    private final EeGrantManage grantManage;

    private final EeOrgManage orgManage;

    public InteractiveAuthServer(EeMenuManage menuManage, EePermissionManage permissionManage, EeBusiResourceManage busiResourceManage,
                                 EeResourceTreeSupport treeSupport, EeGrantManage grantManage, EeOrgManage orgManage) {
        this.menuManage = menuManage;
        this.permissionManage = permissionManage;
        this.busiResourceManage = busiResourceManage;
        this.treeSupport = treeSupport;
        this.grantManage = grantManage;
        this.orgManage = orgManage;
    }

    @Override
    public List<Long> menuIds() {
        return new ArrayList<>(menuManage.currentVisibleMenuIds());
    }

    @Override
    public List<BusiNodeVO> resource(BusiNodeRequest request) {
        Integer rtId = EeResourceTypes.rtId(request.getBusiFlag());
        if (rtId == null) {
            return new ArrayList<>();
        }
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return new ArrayList<>();
        }
        List<PerBusiResource> resources = busiResourceManage.listByOrg(rtId, oid);
        resources.sort(Comparator.comparing(PerBusiResource::getCreateTime).reversed());
        Map<Long, BusiNodeVO> map = new LinkedHashMap<>();
        for (PerBusiResource resource : resources) {
            if (request.getLeaf() != null && !request.getLeaf() && Boolean.TRUE.equals(resource.getLeaf())) {
                continue;
            }
            BusiNodeVO vo = new BusiNodeVO();
            vo.setId(resource.getId());
            vo.setName(resource.getName());
            vo.setLeaf(resource.getLeaf());
            vo.setExtraFlag(resource.getExtraFlag() == null ? 0 : resource.getExtraFlag());
            vo.setExtraFlag1(resource.getExtraFlag1() == null ? 0 : resource.getExtraFlag1());
            map.put(resource.getId(), vo);
        }
        BusiNodeVO root = new BusiNodeVO();
        root.setId(0L);
        root.setName("root");
        root.setLeaf(false);
        root.setChildren(new ArrayList<>());
        for (PerBusiResource resource : resources) {
            BusiNodeVO vo = map.get(resource.getId());
            if (vo == null) {
                continue;
            }
            BusiNodeVO parent = resource.getPid() == null ? null : map.get(resource.getPid());
            if (parent != null) {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            } else {
                root.getChildren().add(vo);
            }
        }
        List<BusiNodeVO> nodes = new ArrayList<>();
        if (request.getLeaf() == null || !request.getLeaf()) {
            nodes.add(root);
        } else {
            nodes.addAll(root.getChildren());
        }
        return treeSupport.filter(nodes, rtId);
    }

    @Override
    @Transactional
    public void saveResource(BusiResourceCreator creator) {
        Integer rtId = EeResourceTypes.rtId(creator.getFlag());
        if (rtId == null) {
            return;
        }
        treeSupport.register(rtId, creator.getId(), creator.getName(), creator.getPid(), Boolean.TRUE.equals(creator.getLeaf()),
                creator.getExtraFlag(), creator.getExtraFlag1());
    }

    @Override
    public void editResource(BusiResourceEditor editor) {
        Integer rtId = EeResourceTypes.rtId(editor.getFlag());
        PerBusiResource exist = busiResourceManage.get(editor.getId());
        if (rtId == null || exist == null) {
            return;
        }
        busiResourceManage.save(rtId, editor.getId(), editor.getName(), null, Boolean.TRUE.equals(exist.getLeaf()), null,
                editor.getExtraFlag(), editor.getExtraFlag1());
    }

    @Override
    public void editResourceExtraFlag(BusiResourceEditor editor) {
        editResource(editor);
    }

    @Override
    public void delResource(Long id) {
        treeSupport.remove(id);
    }

    @Override
    public boolean checkDel(Long id) {
        PerBusiResource resource = busiResourceManage.get(id);
        if (resource == null) {
            return true;
        }
        return permissionManage.current(resource.getRtId(), id).satisfies(EeConstants.WEIGHT_MANAGE);
    }

    @Override
    public void moveResource(BusiResourceMover mover) {
        busiResourceManage.move(mover.getId(), mover.getPid());
    }

    @Override
    public void checkAuth(BusiPerCheckDTO checkDTO) {
        if (checkDTO == null || checkDTO.getBusiEnum() == null || checkDTO.getId() == null) {
            return;
        }
        if (V3UserUtil.isSysAdmin()) {
            return;
        }
        int level = checkDTO.getAuthEnum() == null ? EeConstants.WEIGHT_READ : checkDTO.getAuthEnum().getWeight();
        if (!permissionManage.current(checkDTO.getBusiEnum().getFlag(), checkDTO.getId()).satisfies(level)) {
            DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), Translator.get("ee_no_permission"));
        }
    }

    @Override
    public PermissionValVO queryAuth(Long id) {
        PerBusiResource resource = busiResourceManage.get(id);
        if (resource == null) {
            return V3UserUtil.isSysAdmin() ? new PermissionValVO(EeConstants.WEIGHT_AUTH, EeConstants.EXT_ALL) : new PermissionValVO(0, 0);
        }
        Perm perm = permissionManage.current(resource.getRtId(), id);
        return new PermissionValVO(perm.weight(), perm.displayExt());
    }

    @Override
    public List<ResourceNodeVO> query2Root(Long id, Integer flag, Integer logOT) {
        List<ResourceNodeVO> result = new ArrayList<>();
        PerBusiResource current = busiResourceManage.get(id);
        Set<Long> visited = new HashSet<>();
        while (current != null && visited.add(current.getId())) {
            result.add(new ResourceNodeVO(current.getId(), current.getName()));
            current = current.getPid() == null || current.getPid() <= 0 ? null : busiResourceManage.get(current.getPid());
        }
        Collections.reverse(result);
        return result;
    }

    @Override
    public boolean checkEmpty() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return true;
        }
        for (int rtId : new int[]{EeConstants.RT_PANEL, EeConstants.RT_SCREEN, EeConstants.RT_DATASET, EeConstants.RT_DATASOURCE}) {
            if (!busiResourceManage.idsByOrg(rtId, oid).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String OrgNameForResource(ExportTaskDTO exportTaskDTO) {
        if (exportTaskDTO == null || exportTaskDTO.getExportFrom() == null) {
            return null;
        }
        Long oid = busiResourceManage.orgOf(exportTaskDTO.getExportFrom());
        PerOrg org = orgManage.get(oid);
        return org == null ? null : org.getName();
    }

    @Override
    @Transactional
    public void batchAuthorize(BusiBatchAuthorizeRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getNodeList())) {
            return;
        }
        Long uid = V3UserUtil.getUid();
        Long oid = request.getOid() == null ? V3UserUtil.getOid() : request.getOid();
        if (uid == null || oid == null || permissionManage.isOrgAdmin(uid, oid)) {
            return;
        }
        for (BusiBatchAuthorizeNode node : request.getNodeList()) {
            if (CollectionUtils.isEmpty(node.getIdList())) {
                continue;
            }
            for (Long id : node.getIdList()) {
                grantManage.save(EeConstants.SUBJECT_USER, uid, node.getFlag(), id, EeConstants.WEIGHT_AUTH, EeConstants.EXT_ALL, oid);
            }
        }
    }

    @Override
    public void revert() {
        // 自研企业版无许可证回退场景
    }
}
