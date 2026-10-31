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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 当前用户可切换到的组织：系统管理员为全部组织，其他用户为其所属的组织
     */
    private List<PerOrg> switchableOrgs() {
        QPerOrg q = QPerOrg.perOrg;
        if (V3UserUtil.isSysAdmin()) {
            return queryFactory.selectFrom(q).orderBy(q.createTime.asc()).fetch();
        }
        List<Long> oids = orgManage.memberOids(V3UserUtil.getUid());
        if (oids.isEmpty()) {
            return new ArrayList<>();
        }
        return queryFactory.selectFrom(q).where(q.id.in(oids)).orderBy(q.createTime.asc()).fetch();
    }

    /**
     * 在 orgs 基础上补齐祖先组织（祖先不在 orgs 中时只用于展示层级，标记为只读）
     */
    private Map<Long, PerOrg> withAncestors(Collection<PerOrg> orgs) {
        Map<Long, PerOrg> all = new LinkedHashMap<>();
        orgs.forEach(org -> all.put(org.getId(), org));
        Set<Long> missing = new LinkedHashSet<>();
        for (PerOrg org : orgs) {
            for (Long ancestor : orgManage.ancestors(org.getId())) {
                if (!all.containsKey(ancestor)) {
                    missing.add(ancestor);
                }
            }
        }
        if (!missing.isEmpty()) {
            QPerOrg q = QPerOrg.perOrg;
            queryFactory.selectFrom(q).where(q.id.in(missing)).fetch().forEach(org -> all.put(org.getId(), org));
        }
        List<PerOrg> sorted = new ArrayList<>(all.values());
        sorted.sort(Comparator.comparing(PerOrg::getCreateTime, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<Long, PerOrg> result = new LinkedHashMap<>();
        sorted.forEach(org -> result.put(org.getId(), org));
        return result;
    }

    /**
     * 组织树：用户所属的组织（系统管理员为全部组织）以及当前身份可管理的组织（日志等页面的组织筛选使用），
     * 为保持层级补齐的祖先组织标记为只读
     */
    @Override
    public List<MountedVO> mounted(KeywordRequest request) {
        Map<Long, PerOrg> selectable = new LinkedHashMap<>();
        switchableOrgs().forEach(org -> selectable.put(org.getId(), org));
        visibleOrgs().forEach(org -> selectable.putIfAbsent(org.getId(), org));
        Map<Long, PerOrg> orgs = withAncestors(selectable.values());
        String keyword = request == null || StringUtils.isBlank(request.getKeyword()) ? null : request.getKeyword().trim().toLowerCase();
        Map<Long, MountedVO> map = new LinkedHashMap<>();
        for (PerOrg org : orgs.values()) {
            MountedVO vo = new MountedVO();
            vo.setId(org.getId());
            vo.setName(org.getName());
            vo.setReadOnly(!selectable.containsKey(org.getId()));
            vo.setChildren(new ArrayList<>());
            map.put(org.getId(), vo);
        }
        List<MountedVO> roots = new ArrayList<>();
        for (PerOrg org : orgs.values()) {
            MountedVO vo = map.get(org.getId());
            if (keyword != null) {
                if (!vo.isReadOnly() && org.getName().toLowerCase().contains(keyword)) {
                    roots.add(vo);
                }
                continue;
            }
            if (org.getPid() != null && map.containsKey(org.getPid())) {
                map.get(org.getPid()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        map.values().forEach(vo -> vo.setLeaf(vo.getChildren().isEmpty()));
        return roots;
    }

    /**
     * 页头组织切换器的懒加载组织树。
     * <ul>
     *     <li>无 pid、无 keyword：根节点；有 pid：该组织的下级节点；</li>
     *     <li>有 keyword：名称匹配的组织及其祖先组成的完整树，expandKeyList 为需要展开的节点；</li>
     *     <li>节点只包含用户可切换的组织（所属组织，系统管理员为全部组织）及其祖先，祖先不可切换时 readOnly=true；</li>
     *     <li>name 为当前组织名称，switchable 为可切换的组织数量（页头据此决定是否显示切换器）。</li>
     * </ul>
     */
    @PostMapping("/lazyMounted")
    public Map<String, Object> lazyMounted(@RequestBody(required = false) OrgLazyRequest request) {
        List<PerOrg> switchable = switchableOrgs();
        Set<Long> selectable = switchable.stream().map(PerOrg::getId).collect(Collectors.toSet());
        Map<Long, PerOrg> orgs = withAncestors(switchable);
        Map<Long, List<PerOrg>> children = new HashMap<>();
        List<PerOrg> roots = new ArrayList<>();
        for (PerOrg org : orgs.values()) {
            if (org.getPid() != null && orgs.containsKey(org.getPid())) {
                children.computeIfAbsent(org.getPid(), key -> new ArrayList<>()).add(org);
            } else {
                roots.add(org);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        PerOrg current = orgManage.get(V3UserUtil.getOid());
        result.put("name", current == null ? null : current.getName());
        result.put("switchable", selectable.size());
        List<String> expandKeys = new ArrayList<>();
        String keyword = request == null || StringUtils.isBlank(request.getKeyword()) ? null : request.getKeyword().trim().toLowerCase();
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (keyword != null) {
            Set<Long> keep = new HashSet<>();
            for (PerOrg org : orgs.values()) {
                if (org.getName().toLowerCase().contains(keyword)) {
                    PerOrg cursor = org;
                    while (cursor != null && keep.add(cursor.getId())) {
                        cursor = cursor.getPid() == null ? null : orgs.get(cursor.getPid());
                    }
                }
            }
            for (PerOrg root : roots) {
                Map<String, Object> node = switcherNode(root, children, selectable, keep, expandKeys);
                if (node != null) {
                    nodes.add(node);
                }
            }
        } else {
            Long pid = request == null ? null : request.getPid();
            List<PerOrg> level = pid == null ? roots : children.getOrDefault(pid, new ArrayList<>());
            for (PerOrg org : level) {
                nodes.add(switcherNode(org, children, selectable, null, null));
            }
        }
        result.put("nodes", nodes);
        result.put("expandKeyList", expandKeys);
        return result;
    }

    private Map<String, Object> switcherNode(PerOrg org, Map<Long, List<PerOrg>> children, Set<Long> selectable,
                                             Set<Long> keep, List<String> expandKeys) {
        if (keep != null && !keep.contains(org.getId())) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", String.valueOf(org.getId()));
        node.put("pid", org.getPid() == null ? null : String.valueOf(org.getPid()));
        node.put("name", org.getName());
        node.put("readOnly", !selectable.contains(org.getId()));
        List<PerOrg> kids = children.getOrDefault(org.getId(), new ArrayList<>());
        node.put("leaf", kids.isEmpty());
        if (keep != null) {
            List<Map<String, Object>> childNodes = new ArrayList<>();
            for (PerOrg kid : kids) {
                Map<String, Object> child = switcherNode(kid, children, selectable, keep, expandKeys);
                if (child != null) {
                    childNodes.add(child);
                }
            }
            if (!childNodes.isEmpty()) {
                expandKeys.add(String.valueOf(org.getId()));
                node.put("children", childNodes);
            }
        }
        return node;
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
