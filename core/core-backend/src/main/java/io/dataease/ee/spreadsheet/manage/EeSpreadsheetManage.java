package io.dataease.ee.spreadsheet.manage;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.operation.RecentResourceApi;
import io.dataease.api.spreadsheet.dto.SpreadsheetCreator;
import io.dataease.api.spreadsheet.dto.SpreadsheetEditor;
import io.dataease.api.spreadsheet.vo.SpreadsheetTreeVO;
import io.dataease.api.spreadsheet.vo.SpreadsheetVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.ee.permissions.resource.EeResourceTreeSupport;
import io.dataease.ee.spreadsheet.SpreadsheetConstants;
import io.dataease.ee.spreadsheet.dao.*;
import io.dataease.ee.spreadsheet.vo.SpreadsheetDetailVO;
import io.dataease.ee.spreadsheet.vo.SpreadsheetNodeVO;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.BusiNodeRequest;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.system.manage.CoreUserManage;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.visualization.manage.VisualizationStoreManage;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 电子表格资源管理：资源树、草稿/发布版本、目录操作。
 *
 * <p>状态机：新建为 0（未发布）；保存草稿时已发布(1)变为 2（已保存未发布）；发布将草稿复制为发布版本并置 1；
 * 取消发布置 0；恢复到发布版本将发布版本复制回草稿并置 1。</p>
 */
@Component
public class EeSpreadsheetManage {

    private static final int RT = EeConstants.RT_SPREADSHEET;

    private final XpackSpreadsheetRepository repository;

    private final XpackSpreadsheetDatasetRepository datasetRepository;

    private final JPAQueryFactory queryFactory;

    private final EeResourceTreeSupport treeSupport;

    private final EePermissionManage permissionManage;

    private final CoreUserManage coreUserManage;

    @Lazy
    @Autowired(required = false)
    private RecentResourceApi recentResourceApi;

    @Lazy
    @Autowired(required = false)
    private VisualizationStoreManage storeManage;

    public EeSpreadsheetManage(XpackSpreadsheetRepository repository, XpackSpreadsheetDatasetRepository datasetRepository,
                               JPAQueryFactory queryFactory, EeResourceTreeSupport treeSupport,
                               EePermissionManage permissionManage, CoreUserManage coreUserManage) {
        this.repository = repository;
        this.datasetRepository = datasetRepository;
        this.queryFactory = queryFactory;
        this.treeSupport = treeSupport;
        this.permissionManage = permissionManage;
        this.coreUserManage = coreUserManage;
    }

    // ------------------------------------------------------------------ 资源树

    /**
     * 当前组织的电子表格资源树，首个元素为虚拟根节点 {id:0,name:root}；同级按创建时间倒序。
     * <p>leaf=false 时仅返回文件夹；weight>=7 时仅保留可管理的节点（不可管理的祖先目录 weight 置 0）。</p>
     */
    public List<SpreadsheetTreeVO> tree(BusiNodeRequest request) {
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        if (uid == null || oid == null) {
            return new ArrayList<>();
        }
        boolean foldersOnly = request != null && Boolean.FALSE.equals(request.getLeaf());
        int requiredWeight = request == null || request.getWeight() == null ? 0 : request.getWeight();

        QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
        var query = queryFactory.select(q.id, q.name, q.pid, q.nodeType, q.status, q.createTime)
                .from(q)
                .where(q.orgId.eq(oid))
                .orderBy(q.createTime.desc(), q.id.desc());
        if (foldersOnly) {
            query.where(q.nodeType.eq(SpreadsheetConstants.NODE_FOLDER));
        }
        List<Tuple> rows = query.fetch();

        SpreadsheetNodeVO root = new SpreadsheetNodeVO();
        root.setId(0L);
        root.setName("root");
        root.setPid(-1L);
        root.setLeaf(false);
        root.setNodeType(SpreadsheetConstants.NODE_FOLDER);
        root.setExtraFlag1(0);

        Map<Long, SpreadsheetNodeVO> nodes = new LinkedHashMap<>();
        for (Tuple row : rows) {
            SpreadsheetNodeVO node = new SpreadsheetNodeVO();
            node.setId(row.get(q.id));
            node.setName(row.get(q.name));
            node.setPid(ObjectUtils.defaultIfNull(row.get(q.pid), 0L));
            String nodeType = row.get(q.nodeType);
            boolean leaf = !StringUtils.equals(nodeType, SpreadsheetConstants.NODE_FOLDER);
            node.setNodeType(nodeType);
            node.setLeaf(leaf);
            node.setCreateTime(row.get(q.createTime));
            if (leaf) {
                int status = ObjectUtils.defaultIfNull(row.get(q.status), SpreadsheetConstants.STATUS_UNPUBLISHED);
                node.setStatus(status);
                node.setExtraFlag1(status);
            }
            nodes.put(node.getId(), node);
        }
        Map<Long, List<SpreadsheetTreeVO>> childMap = new HashMap<>();
        for (SpreadsheetNodeVO node : nodes.values()) {
            Long parent = nodes.containsKey(node.getPid()) ? node.getPid() : 0L;
            childMap.computeIfAbsent(parent, key -> new ArrayList<>()).add(node);
        }

        Map<Long, Perm> perms = permissionManage.effective(uid, oid, RT);
        boolean sysAdmin = permissionManage.isSysAdmin(uid);
        int rootWeight = sysAdmin || permissionManage.canManageRoot(uid, oid, RT) ? EeConstants.WEIGHT_MANAGE : EeConstants.WEIGHT_READ;
        List<SpreadsheetTreeVO> rootChildren = filterChildren(0L, childMap, perms, sysAdmin, requiredWeight, new HashSet<>());
        root.setChildren(rootChildren.isEmpty() ? null : rootChildren);
        root.setWeight(rootWeight < requiredWeight ? 0 : rootWeight);
        root.setExt(0);
        List<SpreadsheetTreeVO> result = new ArrayList<>();
        result.add(root);
        return result;
    }

    private List<SpreadsheetTreeVO> filterChildren(Long pid, Map<Long, List<SpreadsheetTreeVO>> childMap, Map<Long, Perm> perms,
                                                   boolean sysAdmin, int requiredWeight, Set<Long> visited) {
        List<SpreadsheetTreeVO> kept = new ArrayList<>();
        if (!visited.add(pid)) {
            return kept;
        }
        for (SpreadsheetTreeVO item : childMap.getOrDefault(pid, Collections.emptyList())) {
            SpreadsheetNodeVO node = (SpreadsheetNodeVO) item;
            List<SpreadsheetTreeVO> children = Boolean.TRUE.equals(node.getLeaf())
                    ? Collections.emptyList()
                    : filterChildren(node.getId(), childMap, perms, sysAdmin, requiredWeight, visited);
            Perm perm = sysAdmin ? Perm.FULL : perms.get(node.getId());
            boolean has = perm != null && !perm.isNone() && perm.weight() >= Math.max(requiredWeight, 1);
            if (!has && children.isEmpty()) {
                continue;
            }
            if (has) {
                node.setWeight(perm.weight());
                node.setExt(perm.displayExt());
            } else {
                // 仅因下级可见而保留的祖先目录
                node.setWeight(requiredWeight > EeConstants.WEIGHT_READ ? 0 : EeConstants.WEIGHT_READ);
                node.setExt(0);
            }
            node.setChildren(children.isEmpty() ? null : children);
            kept.add(node);
        }
        return kept;
    }

    // ------------------------------------------------------------------ 新建 / 保存

    @Transactional
    public SpreadsheetVO createFolder(SpreadsheetCreator creator) {
        if (creator == null) {
            DEException.throwException(Translator.get("ee_sheet_name_empty"));
        }
        Long oid = currentOid();
        String name = checkName(creator.getName());
        Long pid = normalizePid(creator.getPid());
        checkParentFolder(pid, oid);
        checkDuplicate(oid, pid, name, SpreadsheetConstants.NODE_FOLDER, null);
        long now = System.currentTimeMillis();
        XpackSpreadsheet po = new XpackSpreadsheet();
        po.setId(IDUtils.snowID());
        po.setName(name);
        po.setPid(pid);
        po.setNodeType(SpreadsheetConstants.NODE_FOLDER);
        po.setOrgId(oid);
        po.setVersion(1);
        po.setStatus(SpreadsheetConstants.STATUS_UNPUBLISHED);
        po.setCreateBy(V3UserUtil.getUid());
        po.setCreateTime(now);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(now);
        repository.save(po);
        treeSupport.register(RT, po.getId(), name, pid, false, 0, 0);
        return toVO(po, false);
    }

    @Transactional
    public SpreadsheetVO create(SpreadsheetEditor editor) {
        if (editor == null) {
            DEException.throwException(Translator.get("ee_sheet_name_empty"));
        }
        Long oid = currentOid();
        String name = checkName(editor.getName());
        Long pid = normalizePid(editor.getPid());
        checkParentFolder(pid, oid);
        checkDuplicate(oid, pid, name, SpreadsheetConstants.NODE_SHEET, null);
        long now = System.currentTimeMillis();
        XpackSpreadsheet po = new XpackSpreadsheet();
        po.setId(IDUtils.snowID());
        po.setName(name);
        po.setPid(pid);
        po.setNodeType(SpreadsheetConstants.NODE_SHEET);
        po.setOrgId(oid);
        po.setSheetData(editor.getSheetData());
        po.setRemark(StringUtils.defaultString(editor.getRemark()));
        po.setVersion(1);
        po.setStatus(SpreadsheetConstants.STATUS_UNPUBLISHED);
        po.setCreateBy(V3UserUtil.getUid());
        po.setCreateTime(now);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(now);
        repository.save(po);
        treeSupport.register(RT, po.getId(), name, pid, true, 0, SpreadsheetConstants.STATUS_UNPUBLISHED);
        refreshDatasets(po.getId(), po.getSheetData(), false);
        return toVO(po, true);
    }

    /**
     * 保存草稿：已发布(1)的表格变为已保存未发布(2)；version 递增，提交的 version 落后于当前版本时拒绝（乐观锁）。
     */
    @Transactional
    public SpreadsheetVO update(SpreadsheetEditor editor) {
        if (editor == null || editor.getId() == null) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        XpackSpreadsheet po = requireSheet(editor.getId());
        int current = ObjectUtils.defaultIfNull(po.getVersion(), 1);
        if (editor.getVersion() != null && editor.getVersion() < current) {
            DEException.throwException(Translator.get("ee_sheet_version_conflict"));
        }
        if (StringUtils.isNotBlank(editor.getName()) && !StringUtils.equals(editor.getName().trim(), po.getName())) {
            String name = checkName(editor.getName());
            checkDuplicate(po.getOrgId(), po.getPid(), name, SpreadsheetConstants.NODE_SHEET, po.getId());
            po.setName(name);
            treeSupport.update(RT, po.getId(), name, null, true, null, null);
        }
        if (editor.getSheetData() != null) {
            po.setSheetData(editor.getSheetData());
        }
        if (editor.getRemark() != null) {
            po.setRemark(editor.getRemark());
        }
        int status = ObjectUtils.defaultIfNull(po.getStatus(), SpreadsheetConstants.STATUS_UNPUBLISHED);
        if (status == SpreadsheetConstants.STATUS_PUBLISHED) {
            po.setStatus(SpreadsheetConstants.STATUS_SAVED_UNPUBLISHED);
            treeSupport.update(RT, po.getId(), null, null, true, null, SpreadsheetConstants.STATUS_SAVED_UNPUBLISHED);
        }
        po.setVersion(current + 1);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
        refreshDatasets(po.getId(), po.getSheetData(), false);
        return toVO(po, true);
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 发布版本（预览/嵌入使用），未发布时不返回工作簿数据
     */
    public SpreadsheetVO findPublished(Long id, boolean recordRecent) {
        XpackSpreadsheet po = requireSheet(id);
        SpreadsheetDetailVO vo = toVO(po, false);
        int status = ObjectUtils.defaultIfNull(po.getStatus(), SpreadsheetConstants.STATUS_UNPUBLISHED);
        vo.setSheetData(status == SpreadsheetConstants.STATUS_UNPUBLISHED ? null : po.getPublishedData());
        vo.setWeight(permissionManage.isSysAdmin(V3UserUtil.getUid()) ? EeConstants.WEIGHT_AUTH : permissionManage.current(RT, id).weight());
        if (storeManage != null && status != SpreadsheetConstants.STATUS_UNPUBLISHED) {
            try {
                vo.setFavorite(storeManage.favorited(id));
            } catch (Exception e) {
                LogUtil.warn("query spreadsheet favorite failed: " + e.getMessage());
            }
        }
        if (recordRecent && recentResourceApi != null && status != SpreadsheetConstants.STATUS_UNPUBLISHED) {
            try {
                recentResourceApi.recordSpreadsheet(id);
            } catch (Exception e) {
                LogUtil.warn("record spreadsheet recent failed: " + e.getMessage());
            }
        }
        return vo;
    }

    /**
     * 草稿（编辑器使用）
     */
    public SpreadsheetVO findEdit(Long id) {
        XpackSpreadsheet po = requireSheet(id);
        return toVO(po, true);
    }

    public boolean canRead(Long id) {
        Long uid = V3UserUtil.getUid();
        return uid != null && (permissionManage.isSysAdmin(uid) || permissionManage.current(RT, id).satisfies(EeConstants.WEIGHT_READ));
    }

    /**
     * 名称是否已存在（同目录、同类型）
     */
    public boolean nameExists(SpreadsheetEditor editor) {
        if (editor == null || StringUtils.isBlank(editor.getName())) {
            return false;
        }
        Long oid = currentOid();
        String nodeType = StringUtils.equals(editor.getNodeType(), SpreadsheetConstants.NODE_FOLDER)
                ? SpreadsheetConstants.NODE_FOLDER : SpreadsheetConstants.NODE_SHEET;
        Long pid = normalizePid(editor.getPid());
        Long excludeId = editor.getId();
        if (excludeId != null && editor.getPid() == null) {
            XpackSpreadsheet exist = repository.findById(excludeId).orElse(null);
            if (exist != null) {
                pid = exist.getPid();
            }
        }
        return duplicated(oid, pid, editor.getName().trim(), nodeType, excludeId);
    }

    // ------------------------------------------------------------------ 发布状态

    @Transactional
    public SpreadsheetVO updateStatus(SpreadsheetEditor editor) {
        if (editor == null || editor.getId() == null) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        XpackSpreadsheet po = requireSheet(editor.getId());
        Integer target = editor.getStatus();
        if (!Objects.equals(target, SpreadsheetConstants.STATUS_PUBLISHED) && !Objects.equals(target, SpreadsheetConstants.STATUS_UNPUBLISHED)) {
            DEException.throwException(Translator.get("ee_sheet_status_invalid"));
        }
        long now = System.currentTimeMillis();
        if (target == SpreadsheetConstants.STATUS_PUBLISHED) {
            po.setPublishedData(StringUtils.defaultString(po.getSheetData()));
            po.setPublishBy(V3UserUtil.getUid());
            po.setPublishTime(now);
            refreshDatasets(po.getId(), po.getPublishedData(), true);
        }
        po.setStatus(target);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(now);
        repository.save(po);
        treeSupport.update(RT, po.getId(), null, null, true, null, target);
        return toVO(po, false);
    }

    /**
     * 放弃草稿、恢复到发布版本
     */
    @Transactional
    public SpreadsheetVO recoverToPublished(Long id) {
        XpackSpreadsheet po = requireSheet(id);
        int status = ObjectUtils.defaultIfNull(po.getStatus(), SpreadsheetConstants.STATUS_UNPUBLISHED);
        if (po.getPublishedData() == null || status == SpreadsheetConstants.STATUS_UNPUBLISHED) {
            DEException.throwException(Translator.get("ee_sheet_not_published"));
        }
        po.setSheetData(po.getPublishedData());
        po.setStatus(SpreadsheetConstants.STATUS_PUBLISHED);
        po.setVersion(ObjectUtils.defaultIfNull(po.getVersion(), 1) + 1);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
        refreshDatasets(po.getId(), po.getSheetData(), false);
        treeSupport.update(RT, po.getId(), null, null, true, null, SpreadsheetConstants.STATUS_PUBLISHED);
        return toVO(po, true);
    }

    // ------------------------------------------------------------------ 目录操作

    @Transactional
    public void rename(SpreadsheetEditor editor) {
        if (editor == null || editor.getId() == null) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        if (Boolean.TRUE.equals(editor.getOrgRoot())) {
            return;
        }
        XpackSpreadsheet po = requireNode(editor.getId());
        String name = checkName(editor.getName());
        if (StringUtils.equals(name, po.getName())) {
            return;
        }
        checkDuplicate(po.getOrgId(), po.getPid(), name, po.getNodeType(), po.getId());
        po.setName(name);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
        treeSupport.update(RT, po.getId(), name, null, isLeaf(po), null, null);
    }

    @Transactional
    public void move(SpreadsheetEditor editor) {
        if (editor == null || editor.getId() == null) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        XpackSpreadsheet po = requireNode(editor.getId());
        Long pid = normalizePid(editor.getPid());
        if (Objects.equals(pid, po.getPid())) {
            return;
        }
        checkParentFolder(pid, po.getOrgId());
        if (Objects.equals(pid, po.getId()) || (!isLeaf(po) && descendantIds(po.getId()).contains(pid))) {
            DEException.throwException(Translator.get("ee_sheet_move_invalid"));
        }
        checkDuplicate(po.getOrgId(), pid, po.getName(), po.getNodeType(), po.getId());
        po.setPid(pid);
        po.setUpdateBy(V3UserUtil.getUid());
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
        treeSupport.update(RT, po.getId(), null, pid, isLeaf(po), null, null);
    }

    /**
     * 删除资源及全部下级；rootOrgNode 为组织同名虚拟目录（无自身记录），只删除其下级
     */
    @Transactional
    public void delete(Long id, boolean rootOrgNode) {
        if (id == null) {
            return;
        }
        Long oid = currentOid();
        List<Long> tops;
        if (rootOrgNode) {
            QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
            tops = queryFactory.select(q.id).from(q).where(q.pid.eq(id), q.orgId.eq(oid)).fetch();
        } else {
            requireNode(id);
            tops = List.of(id);
        }
        for (Long top : tops) {
            deleteInternal(top);
        }
    }

    /**
     * 不做组织校验的级联删除（删除组织时调用）
     */
    @Transactional
    public void deleteInternal(Long id) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(id);
        ids.addAll(descendantIds(id));
        List<Long> idList = new ArrayList<>(ids);
        QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
        queryFactory.delete(q).where(q.id.in(idList)).execute();
        QXpackSpreadsheetDataset d = QXpackSpreadsheetDataset.xpackSpreadsheetDataset;
        queryFactory.delete(d).where(d.spreadsheetId.in(idList)).execute();
        treeSupport.remove(id);
        if (storeManage != null) {
            try {
                storeManage.deleteByResourceIds(idList);
            } catch (Exception e) {
                LogUtil.warn("delete spreadsheet favorites failed: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 数据集引用

    /**
     * 当前用户是否可通过某个可查看的电子表格访问该数据集（与仪表板“通过可视化读取数据集”一致）。
     * 查看者仅认可发布版本中的引用；对电子表格有管理权限者同时认可草稿中的引用。
     */
    public boolean datasetReadableThroughSpreadsheet(Long datasetId) {
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        if (datasetId == null || uid == null || oid == null) {
            return false;
        }
        QXpackSpreadsheetDataset d = QXpackSpreadsheetDataset.xpackSpreadsheetDataset;
        QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
        List<Tuple> refs = queryFactory.select(d.spreadsheetId, d.published, q.status)
                .from(d).innerJoin(q).on(q.id.eq(d.spreadsheetId))
                .where(d.datasetId.eq(datasetId), q.orgId.eq(oid))
                .fetch();
        if (refs.isEmpty()) {
            return false;
        }
        Map<Long, Perm> perms = permissionManage.effective(uid, oid, RT);
        for (Tuple ref : refs) {
            Perm perm = perms.get(ref.get(d.spreadsheetId));
            if (perm == null || perm.isNone()) {
                continue;
            }
            boolean published = Boolean.TRUE.equals(ref.get(d.published));
            int status = ObjectUtils.defaultIfNull(ref.get(q.status), SpreadsheetConstants.STATUS_UNPUBLISHED);
            if (published && status != SpreadsheetConstants.STATUS_UNPUBLISHED) {
                return true;
            }
            if (perm.satisfies(EeConstants.WEIGHT_MANAGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 电子表格（草稿或发布版本）引用的数据集ID
     */
    public List<Long> datasetIds(Long spreadsheetId, boolean published) {
        QXpackSpreadsheetDataset d = QXpackSpreadsheetDataset.xpackSpreadsheetDataset;
        return queryFactory.select(d.datasetId).from(d)
                .where(d.spreadsheetId.eq(spreadsheetId), d.published.eq(published)).fetch();
    }

    private void refreshDatasets(Long spreadsheetId, String sheetData, boolean published) {
        QXpackSpreadsheetDataset d = QXpackSpreadsheetDataset.xpackSpreadsheetDataset;
        queryFactory.delete(d).where(d.spreadsheetId.eq(spreadsheetId), d.published.eq(published)).execute();
        Set<Long> datasetIds = SpreadsheetDatasetExtractor.extract(sheetData);
        if (CollectionUtils.isEmpty(datasetIds)) {
            return;
        }
        List<XpackSpreadsheetDataset> list = new ArrayList<>();
        for (Long datasetId : datasetIds) {
            XpackSpreadsheetDataset item = new XpackSpreadsheetDataset();
            item.setId(IDUtils.snowID());
            item.setSpreadsheetId(spreadsheetId);
            item.setDatasetId(datasetId);
            item.setPublished(published);
            list.add(item);
        }
        datasetRepository.saveAll(list);
    }

    // ------------------------------------------------------------------ 工具

    private Long currentOid() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return oid;
    }

    /**
     * 当前组织内的节点（文件夹或电子表格）
     */
    private XpackSpreadsheet requireNode(Long id) {
        XpackSpreadsheet po = id == null ? null : repository.findById(id).orElse(null);
        if (po == null || !Objects.equals(po.getOrgId(), V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        return po;
    }

    private XpackSpreadsheet requireSheet(Long id) {
        XpackSpreadsheet po = requireNode(id);
        if (!isLeaf(po)) {
            DEException.throwException(Translator.get("ee_sheet_not_exist"));
        }
        return po;
    }

    private static boolean isLeaf(XpackSpreadsheet po) {
        return !StringUtils.equals(po.getNodeType(), SpreadsheetConstants.NODE_FOLDER);
    }

    private static Long normalizePid(Long pid) {
        return pid == null || pid < 0 ? 0L : pid;
    }

    private void checkParentFolder(Long pid, Long oid) {
        if (pid == 0L) {
            return;
        }
        XpackSpreadsheet parent = repository.findById(pid).orElse(null);
        if (parent == null || isLeaf(parent) || !Objects.equals(parent.getOrgId(), oid)) {
            DEException.throwException(Translator.get("ee_sheet_folder_not_exist"));
        }
    }

    private static String checkName(String name) {
        String trimmed = StringUtils.trim(name);
        if (StringUtils.isBlank(trimmed)) {
            DEException.throwException(Translator.get("ee_sheet_name_empty"));
        }
        if (trimmed.length() > SpreadsheetConstants.NAME_MAX_LENGTH) {
            DEException.throwException(Translator.get("ee_sheet_name_too_long", SpreadsheetConstants.NAME_MAX_LENGTH));
        }
        return trimmed;
    }

    private void checkDuplicate(Long oid, Long pid, String name, String nodeType, Long excludeId) {
        if (duplicated(oid, pid, name, nodeType, excludeId)) {
            DEException.throwException(Translator.get("ee_sheet_name_repeat"));
        }
    }

    private boolean duplicated(Long oid, Long pid, String name, String nodeType, Long excludeId) {
        QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
        var query = queryFactory.select(q.id).from(q)
                .where(q.orgId.eq(oid), q.pid.eq(pid), q.name.eq(name), q.nodeType.eq(nodeType));
        if (excludeId != null) {
            query.where(q.id.ne(excludeId));
        }
        return query.fetchFirst() != null;
    }

    private Set<Long> descendantIds(Long id) {
        QXpackSpreadsheet q = QXpackSpreadsheet.xpackSpreadsheet;
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(id);
        Set<Long> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (Long kid : queryFactory.select(q.id).from(q).where(q.pid.eq(current)).fetch()) {
                if (!Objects.equals(kid, id) && result.add(kid)) {
                    stack.push(kid);
                }
            }
        }
        return result;
    }

    private SpreadsheetDetailVO toVO(XpackSpreadsheet po, boolean withDraft) {
        SpreadsheetDetailVO vo = new SpreadsheetDetailVO();
        vo.setId(po.getId());
        vo.setName(po.getName());
        vo.setPid(po.getPid());
        vo.setNodeType(po.getNodeType());
        vo.setOrgId(po.getOrgId());
        vo.setVersion(po.getVersion());
        vo.setStatus(po.getStatus());
        vo.setRemark(po.getRemark());
        vo.setCreateTime(po.getCreateTime());
        vo.setUpdateTime(po.getUpdateTime());
        if (po.getCreateBy() != null) {
            vo.setCreateBy(String.valueOf(po.getCreateBy()));
            vo.setCreator(userName(po.getCreateBy()));
        }
        if (po.getUpdateBy() != null) {
            vo.setUpdateBy(String.valueOf(po.getUpdateBy()));
            vo.setUpdater(userName(po.getUpdateBy()));
        }
        if (withDraft) {
            vo.setSheetData(po.getSheetData());
        }
        return vo;
    }

    private String userName(Long uid) {
        try {
            return coreUserManage.getUserName(uid);
        } catch (Exception e) {
            return String.valueOf(uid);
        }
    }
}
