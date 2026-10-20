package io.dataease.ee.permissions.org;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.org.api.OrgApi;
import io.dataease.api.permissions.org.dto.OrgCreator;
import io.dataease.api.permissions.org.dto.OrgDelRequest;
import io.dataease.api.permissions.org.dto.OrgEditor;
import io.dataease.api.permissions.org.dto.OrgLazyRequest;
import io.dataease.api.permissions.org.vo.*;
import io.dataease.ee.permissions.auth.EeEntityPermit;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.QPerOrg;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.KeywordRequest;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController("orgServer")
@RequestMapping("/org")
public class OrgServer implements OrgApi {

    private final JPAQueryFactory queryFactory;

    private final EeOrgManage orgManage;

    private final EeOrgService orgService;

    private final EeEntityPermit entityPermit;

    public OrgServer(JPAQueryFactory queryFactory, EeOrgManage orgManage, EeOrgService orgService, EeEntityPermit entityPermit) {
        this.queryFactory = queryFactory;
        this.orgManage = orgManage;
        this.orgService = orgService;
        this.entityPermit = entityPermit;
    }

    /**
     * 当前身份可管理的组织：系统管理员为全部；其他为当前组织及其下级
     */
    private List<PerOrg> visibleOrgs() {
        QPerOrg q = QPerOrg.perOrg;
        if (V3UserUtil.isSysAdmin() && !V3UserUtil.getProxy().isProxy()) {
            return queryFactory.selectFrom(q).orderBy(q.createTime.asc()).fetch();
        }
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return new ArrayList<>();
        }
        List<Long> ids = orgManage.selfAndChildren(oid);
        return queryFactory.selectFrom(q).where(q.id.in(ids)).orderBy(q.createTime.asc()).fetch();
    }

    @Override
    public List<OrgPageVO> pageTree() {
        List<PerOrg> orgs = visibleOrgs();
        Set<Long> ids = orgs.stream().map(PerOrg::getId).collect(Collectors.toSet());
        Map<Long, OrgPageVO> map = new LinkedHashMap<>();
        for (PerOrg org : orgs) {
            OrgPageVO vo = new OrgPageVO();
            vo.setId(org.getId());
            vo.setName(org.getName());
            vo.setCreateTime(org.getCreateTime());
            vo.setReadOnly(false);
            vo.setChildren(new ArrayList<>());
            map.put(org.getId(), vo);
        }
        List<OrgPageVO> roots = new ArrayList<>();
        for (PerOrg org : orgs) {
            OrgPageVO vo = map.get(org.getId());
            if (org.getPid() != null && ids.contains(org.getPid())) {
                map.get(org.getPid()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        return roots;
    }

    @Override
    public LazyTreeVO lazyPageTree(OrgLazyRequest request) {
        List<PerOrg> orgs = visibleOrgs();
        boolean desc = request.getDesc() == null || request.getDesc();
        Comparator<PerOrg> order = Comparator.comparing(PerOrg::getCreateTime);
        orgs.sort(desc ? order.reversed() : order);
        Set<Long> ids = orgs.stream().map(PerOrg::getId).collect(Collectors.toSet());
        Map<Long, List<PerOrg>> children = new HashMap<>();
        List<PerOrg> roots = new ArrayList<>();
        for (PerOrg org : orgs) {
            if (org.getPid() != null && ids.contains(org.getPid())) {
                children.computeIfAbsent(org.getPid(), key -> new ArrayList<>()).add(org);
            } else {
                roots.add(org);
            }
        }
        LazyTreeVO vo = new LazyTreeVO();
        vo.setExpandKeyList(new ArrayList<>());
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = request.getKeyword().trim().toLowerCase();
            Set<Long> keep = new HashSet<>();
            Map<Long, PerOrg> byId = orgs.stream().collect(Collectors.toMap(PerOrg::getId, item -> item));
            for (PerOrg org : orgs) {
                if (org.getName().toLowerCase().contains(keyword)) {
                    PerOrg current = org;
                    while (current != null && keep.add(current.getId())) {
                        current = current.getPid() == null ? null : byId.get(current.getPid());
                    }
                }
            }
            List<LazyOrgTreeNode> nodes = new ArrayList<>();
            for (PerOrg root : roots) {
                LazyOrgTreeNode node = fullNode(root, children, keep, vo.getExpandKeyList());
                if (node != null) {
                    nodes.add(node);
                }
            }
            vo.setNodes(nodes);
            return vo;
        }
        List<PerOrg> level = request.getPid() == null ? roots : children.getOrDefault(request.getPid(), new ArrayList<>());
        vo.setNodes(level.stream().map(org -> node(org, children)).collect(Collectors.toList()));
        return vo;
    }

    private LazyOrgTreeNode node(PerOrg org, Map<Long, List<PerOrg>> children) {
        LazyOrgTreeNode node = new LazyOrgTreeNode();
        node.setId(org.getId());
        node.setPid(org.getPid());
        node.setName(org.getName());
        node.setCreateTime(org.getCreateTime());
        node.setReadOnly(false);
        node.setHasChildren(children.containsKey(org.getId()));
        return node;
    }

    private LazyOrgTreeNode fullNode(PerOrg org, Map<Long, List<PerOrg>> children, Set<Long> keep, List<String> expandKeys) {
        if (!keep.contains(org.getId())) {
            return null;
        }
        LazyOrgTreeNode node = node(org, children);
        List<LazyOrgTreeNode> kids = new ArrayList<>();
        for (PerOrg child : children.getOrDefault(org.getId(), new ArrayList<>())) {
            LazyOrgTreeNode kid = fullNode(child, children, keep, expandKeys);
            if (kid != null) {
                kids.add(kid);
            }
        }
        if (!kids.isEmpty()) {
            expandKeys.add(String.valueOf(org.getId()));
        }
        node.setChildren(kids);
        return node;
    }

    @Override
    public Long create(OrgCreator creator) {
        Long pid = creator.getPid();
        if (pid != null && pid > 0 && !entityPermit.canManageOrg(pid)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if ((pid == null || pid <= 0) && !V3UserUtil.isSysAdmin()) {
            pid = V3UserUtil.getOid();
        }
        return orgService.create(pid, creator.getName(), creator.getAdminIds());
    }

    @Override
    public void edit(OrgEditor editor) {
        orgService.rename(editor.getId(), editor.getName());
    }

    @Override
    public void delete(OrgDelRequest request) {
        if (!entityPermit.canManageOrg(request.getId())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        orgService.delete(request.getId(), request.isDelResource());
    }

    @Override
    public List<MountedVO> mounted(KeywordRequest request) {
        List<PerOrg> orgs = visibleOrgs();
        String keyword = request == null || StringUtils.isBlank(request.getKeyword()) ? null : request.getKeyword().trim().toLowerCase();
        Set<Long> ids = orgs.stream().map(PerOrg::getId).collect(Collectors.toSet());
        Map<Long, MountedVO> map = new LinkedHashMap<>();
        for (PerOrg org : orgs) {
            MountedVO vo = new MountedVO();
            vo.setId(org.getId());
            vo.setName(org.getName());
            vo.setReadOnly(false);
            vo.setChildren(new ArrayList<>());
            map.put(org.getId(), vo);
        }
        List<MountedVO> roots = new ArrayList<>();
        for (PerOrg org : orgs) {
            MountedVO vo = map.get(org.getId());
            if (keyword != null) {
                if (org.getName().toLowerCase().contains(keyword)) {
                    vo.setLeaf(true);
                    roots.add(vo);
                }
                continue;
            }
            if (org.getPid() != null && ids.contains(org.getPid())) {
                map.get(org.getPid()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        map.values().forEach(vo -> vo.setLeaf(vo.getChildren().isEmpty()));
        return roots;
    }

    @Override
    public List<Long> logScope() {
        if (V3UserUtil.isSysAdmin() && !V3UserUtil.getProxy().isProxy()) {
            return null;
        }
        Long oid = V3UserUtil.getOid();
        return oid == null ? new ArrayList<>() : orgManage.selfAndChildren(oid);
    }

    @Override
    public OrgDetailVO detail(Long oid) {
        PerOrg org = orgManage.get(oid);
        if (org == null) {
            return null;
        }
        return new OrgDetailVO(org.getId(), org.getName(), org.getPid(), org.getRootPath());
    }

    @Override
    public List<String> subOrgs() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return new ArrayList<>();
        }
        return orgManage.selfAndChildren(oid).stream().filter(id -> !id.equals(oid)).map(String::valueOf).toList();
    }
}
