package io.dataease.ee.datafilling.manage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.xpack.dataFilling.dto.DataFillingDTO;
import io.dataease.dao.auto.entity.CoreDatasource;
import io.dataease.datasource.dao.auto.entity.CoreDeEngine;
import io.dataease.datasource.dao.auto.repository.CoreDatasourceRepository;
import io.dataease.datasource.dao.auto.repository.CoreDeEngineRepository;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.datafilling.DfConstants;
import io.dataease.ee.datafilling.dao.entity.DfForm;
import io.dataease.ee.datafilling.dao.entity.QDfForm;
import io.dataease.ee.datafilling.dao.repo.DfFormRepository;
import io.dataease.ee.datafilling.jdbc.DfJdbcSupport;
import io.dataease.ee.datafilling.jdbc.DfSession;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.ee.permissions.resource.EeResourceTreeSupport;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtIndexField;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datasource.dto.DatasetTableDTO;
import io.dataease.extensions.datasource.dto.SimpleDatasourceDTO;
import io.dataease.extensions.datasource.dto.TableField;
import io.dataease.extensions.datasource.factory.ProviderFactory;
import io.dataease.extensions.datasource.vo.DatasourceConfiguration;
import io.dataease.extensions.datasource.vo.XpackPluginsDatasourceVO;
import io.dataease.i18n.Translator;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import io.dataease.model.ResourceDeleteRequest;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * 数据填报表单树与表单定义（含建表/改表）
 */
@Component
public class DfFormManage {

    private static final int NAME_MAX = 255;

    private final DfFormRepository formRepository;

    private final DfAccess access;

    private final EeResourceTreeSupport treeSupport;

    private final EePermissionManage permissionManage;

    private final DfJdbcSupport jdbcSupport;

    private final JPAQueryFactory queryFactory;

    private final CoreDatasourceRepository datasourceRepository;

    private final CoreDeEngineRepository engineRepository;

    private final DfUsers users;

    private final DfTaskManage taskManage;

    private final DfDataManage dataManage;

    public DfFormManage(DfFormRepository formRepository, DfAccess access, EeResourceTreeSupport treeSupport,
                        EePermissionManage permissionManage, DfJdbcSupport jdbcSupport, JPAQueryFactory queryFactory,
                        @Lazy CoreDatasourceRepository datasourceRepository, @Lazy CoreDeEngineRepository engineRepository,
                        DfUsers users, @Lazy DfTaskManage taskManage, @Lazy DfDataManage dataManage) {
        this.formRepository = formRepository;
        this.access = access;
        this.treeSupport = treeSupport;
        this.permissionManage = permissionManage;
        this.jdbcSupport = jdbcSupport;
        this.queryFactory = queryFactory;
        this.datasourceRepository = datasourceRepository;
        this.engineRepository = engineRepository;
        this.users = users;
        this.taskManage = taskManage;
        this.dataManage = dataManage;
    }

    // ------------------------------------------------------------------ tree

    public List<BusiNodeVO> tree(BusiNodeRequest request) {
        Long oid = V3UserUtil.getOid();
        QDfForm q = QDfForm.dfForm;
        var query = queryFactory.selectFrom(q).where(q.orgId.eq(oid));
        boolean foldersOnly = request != null && Boolean.FALSE.equals(request.getLeaf());
        if (foldersOnly) {
            query.where(q.nodeType.eq(DfConstants.NODE_FOLDER));
        }
        List<DfForm> list = query.orderBy(q.createTime.desc(), q.id.desc()).fetch();
        BusiNodeVO root = new BusiNodeVO();
        root.setId(0L);
        root.setName("root");
        root.setLeaf(false);
        root.setChildren(new ArrayList<>());
        Map<Long, BusiNodeVO> nodes = new LinkedHashMap<>();
        for (DfForm form : list) {
            BusiNodeVO node = new BusiNodeVO();
            node.setId(form.getId());
            node.setName(form.getName());
            boolean folder = StringUtils.equals(form.getNodeType(), DfConstants.NODE_FOLDER);
            node.setLeaf(!folder);
            node.setType(folder ? DfConstants.NODE_FOLDER : DfConstants.NODE_FORM);
            node.setChildren(folder ? new ArrayList<>() : null);
            nodes.put(form.getId(), node);
        }
        for (DfForm form : list) {
            BusiNodeVO node = nodes.get(form.getId());
            BusiNodeVO parent = form.getPid() == null || form.getPid() == 0L ? root : nodes.get(form.getPid());
            if (parent == null || parent.getChildren() == null) {
                parent = root;
            }
            parent.getChildren().add(node);
        }
        List<BusiNodeVO> result = treeSupport.filter(new ArrayList<>(List.of(root)), EeConstants.RT_DATA_FILLING);
        if (V3UserUtil.isSysAdmin()) {
            fullWeight(result);
        }
        if (request != null && StringUtils.isNotBlank(request.getId())) {
            removeNode(result, request.getId());
        }
        if (request != null && request.getWeight() != null && request.getWeight() > 0) {
            prune(result, request.getWeight());
        }
        return result;
    }

    private void fullWeight(List<BusiNodeVO> nodes) {
        if (nodes == null) {
            return;
        }
        for (BusiNodeVO node : nodes) {
            node.setWeight(EeConstants.WEIGHT_AUTH);
            fullWeight(node.getChildren());
        }
    }

    private void removeNode(List<BusiNodeVO> nodes, String id) {
        if (nodes == null) {
            return;
        }
        nodes.removeIf(n -> n.getId() != null && n.getId() != 0L && StringUtils.equals(String.valueOf(n.getId()), id));
        nodes.forEach(n -> removeNode(n.getChildren(), id));
    }

    private void prune(List<BusiNodeVO> nodes, int weight) {
        if (nodes == null) {
            return;
        }
        Iterator<BusiNodeVO> it = nodes.iterator();
        while (it.hasNext()) {
            BusiNodeVO node = it.next();
            prune(node.getChildren(), weight);
            boolean hasChildren = CollectionUtils.isNotEmpty(node.getChildren());
            boolean root = node.getId() != null && node.getId() == 0L;
            if (!root && (node.getWeight() == null || node.getWeight() < weight) && !hasChildren) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public DataFillingDTO save(DataFillingDTO dto) {
        if (dto == null) {
            DEException.throwException(Translator.get("ee_df_name_required"));
        }
        Long pid = ObjectUtils.defaultIfNull(dto.getPid(), 0L);
        String name = checkName(dto.getName());
        checkParent(pid);
        access.require(pid, EeConstants.WEIGHT_MANAGE);
        boolean folder = StringUtils.equalsIgnoreCase(dto.getNodeType(), DfConstants.NODE_FOLDER);
        checkDuplicateName(null, pid, name, folder ? DfConstants.NODE_FOLDER : DfConstants.NODE_FORM);
        long now = System.currentTimeMillis();
        DfForm form = new DfForm();
        form.setId(IDUtils.snowID());
        form.setPid(pid);
        form.setName(name);
        form.setOrgId(V3UserUtil.getOid());
        form.setCreateBy(V3UserUtil.getUid());
        form.setCreateTime(now);
        form.setUpdateBy(V3UserUtil.getUid());
        form.setUpdateTime(now);
        if (folder) {
            form.setNodeType(DfConstants.NODE_FOLDER);
        } else {
            form.setNodeType(DfConstants.NODE_FORM);
            createFormDefinition(form, dto);
        }
        formRepository.save(form);
        treeSupport.register(EeConstants.RT_DATA_FILLING, form.getId(), form.getName(), pid, !folder, 0, 1);
        return toDTO(form);
    }

    /**
     * 新建表单：校验数据源、建表或绑定已有表
     */
    private void createFormDefinition(DfForm form, DataFillingDTO dto) {
        Long dsId = dto.getDatasource();
        CoreDatasource ds = checkStorageDatasource(dsId);
        String table = checkTableName(dto.getTableName());
        List<ExtTableField> fields = DfJson.activeFields(dto.getForms());
        if (fields.isEmpty()) {
            DEException.throwException(Translator.get("ee_df_forms_empty"));
        }
        ArrayNode raw = DfJson.array(dto.getForms());
        normalizeNewItems(raw, fields);
        boolean useExists = dto.isUseExistsTable();
        boolean createIndex = Boolean.TRUE.equals(dto.getCreateIndex());
        List<ExtIndexField> indexes = createIndex ? DfJson.indexes(dto.getTableIndexes()) : new ArrayList<>();
        try (DfSession session = jdbcSupport.open(ds)) {
            if (!useExists) {
                checkColumns(fields, Collections.emptyList(), DfConstants.DEFAULT_PK);
                ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
                if (session.tableExists(table)) {
                    DEException.throwException(Translator.get("ee_df_table_exists", table));
                }
                String sql;
                try {
                    sql = ddl.useCreateSqlWithComment() ? ddl.createTableSqlWithComment(table, fields, form.getName())
                            : ddl.createTableSql(table, fields);
                } catch (DEException e) {
                    throw e;
                } catch (Exception e) {
                    DEException.throwException(e.getMessage());
                    return;
                }
                session.execute(sql);
                try {
                    for (String comment : ddl.createComment(table, fields, form.getName())) {
                        try {
                            session.executeOne(comment);
                        } catch (Exception e) {
                            LogUtil.warn("create comment failed: " + e.getMessage());
                        }
                    }
                    if (!indexes.isEmpty()) {
                        for (String s : ddl.createTableIndexSql(table, resolveIndexes(indexes, fields))) {
                            session.execute(s);
                        }
                    }
                } catch (RuntimeException e) {
                    try {
                        session.execute(ddl.dropTableSql(table));
                    } catch (Exception ignore) {
                    }
                    throw e;
                }
                form.setPrimaryKey(DfConstants.DEFAULT_PK);
            } else {
                List<TableField> columns = session.columns(table);
                if (columns.isEmpty()) {
                    DEException.throwException(Translator.get("ee_df_table_not_exists", table));
                }
                List<TableField> pks = columns.stream().filter(TableField::isPrimary).toList();
                if (pks.size() != 1) {
                    DEException.throwException(Translator.get("ee_df_table_no_pk"));
                }
                String pk = pks.getFirst().getOriginName();
                Map<String, String> existing = new HashMap<>();
                columns.forEach(c -> existing.put(c.getOriginName().toLowerCase(), c.getOriginName()));
                List<ExtTableField> toCreate = new ArrayList<>();
                List<ExtTableField> bound = new ArrayList<>();
                for (ExtTableField f : fields) {
                    if (f.getSettings().getMapping().isUseExistsTable()) {
                        bound.add(f);
                    } else {
                        toCreate.add(f);
                    }
                }
                checkColumns(fields, Collections.emptyList(), pk);
                for (String col : columnNames(bound)) {
                    if (!existing.containsKey(col.toLowerCase())) {
                        DEException.throwException(Translator.get("ee_df_column_not_exists", col));
                    }
                }
                for (String col : columnNames(toCreate)) {
                    if (existing.containsKey(col.toLowerCase())) {
                        DEException.throwException(Translator.get("ee_df_column_duplicate", col));
                    }
                }
                if (!toCreate.isEmpty()) {
                    ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
                    session.execute(ddl.addTableColumnSql(table, toCreate, new ArrayList<>()));
                }
                if (!indexes.isEmpty() && session.ddl() != null) {
                    for (String s : session.ddl().createTableIndexSql(table, resolveIndexes(indexes, fields))) {
                        session.execute(s);
                    }
                }
                form.setPrimaryKey(pk);
            }
        }
        form.setDatasource(dsId);
        form.setTableName(table);
        form.setUseExistsTable(useExists);
        form.setCreateIndex(createIndex);
        form.setTableIndexes(StringUtils.defaultIfBlank(dto.getTableIndexes(), "[]"));
        form.setForms(DfJson.write(raw));
    }

    @Transactional
    public DataFillingDTO update(DataFillingDTO dto) {
        DfForm form = access.form(dto.getId());
        access.require(form.getId(), EeConstants.WEIGHT_MANAGE);
        if (!StringUtils.equals(form.getNodeType(), DfConstants.NODE_FORM)) {
            return rename(dto);
        }
        String name = checkName(dto.getName());
        Long pid = dto.getPid() == null ? form.getPid() : dto.getPid();
        if (!Objects.equals(pid, form.getPid())) {
            checkMoveTarget(form, pid);
        }
        checkDuplicateName(form.getId(), pid, name, DfConstants.NODE_FORM);

        List<ExtTableField> stored = DfJson.fields(form.getForms());
        ArrayNode storedRaw = DfJson.array(form.getForms());
        Map<String, ExtTableField> storedById = new HashMap<>();
        Map<String, ObjectNode> storedRawById = new HashMap<>();
        for (int i = 0; i < stored.size(); i++) {
            ExtTableField f = stored.get(i);
            if (StringUtils.isNotBlank(f.getId())) {
                storedById.put(f.getId(), f);
                if (storedRaw.get(i) instanceof ObjectNode obj) {
                    storedRawById.put(f.getId(), obj);
                }
            }
        }
        ArrayNode incomingRaw = DfJson.array(dto.getForms());
        List<ExtTableField> incoming = DfJson.fields(dto.getForms());
        incoming.removeIf(ExtTableField::isRemoved);
        if (incoming.isEmpty()) {
            DEException.throwException(Translator.get("ee_df_forms_empty"));
        }
        ArrayNode result = DfJson.MAPPER.createArrayNode();
        List<ExtTableField> toCreate = new ArrayList<>();
        List<ExtTableField> kept = new ArrayList<>();
        Set<String> incomingIds = new HashSet<>();
        for (int i = 0; i < incomingRaw.size(); i++) {
            if (!(incomingRaw.get(i) instanceof ObjectNode node) || node.path("removed").asBoolean(false)) {
                continue;
            }
            String id = node.path("id").asText(null);
            ExtTableField old = id == null ? null : storedById.get(id);
            if (old != null && !old.isRemoved()) {
                // 旧字段：列名、列类型不可修改
                ObjectNode storedNode = storedRawById.get(id);
                JsonNode storedMapping = storedNode == null ? null : storedNode.path("settings").get("mapping");
                if (storedMapping != null && node.get("settings") instanceof ObjectNode settings) {
                    settings.set("mapping", storedMapping.deepCopy());
                }
                node.remove("old");
                result.add(node);
                kept.add(DfJson.fields("[" + DfJson.write(node) + "]").getFirst());
                incomingIds.add(id);
            } else {
                if (StringUtils.isBlank(id) || storedById.containsKey(id)) {
                    node.put("id", UUID.randomUUID().toString());
                }
                node.remove("old");
                ExtTableField f = DfJson.fields("[" + DfJson.write(node) + "]").getFirst();
                ensureMappingType(node, f);
                f = DfJson.fields("[" + DfJson.write(node) + "]").getFirst();
                result.add(node);
                incomingIds.add(f.getId());
                toCreate.add(f);
            }
        }
        boolean useExistsForm = Boolean.TRUE.equals(form.getUseExistsTable());
        List<ExtTableField> toRename = new ArrayList<>();
        for (ExtTableField f : stored) {
            if (f.isRemoved() || StringUtils.isBlank(f.getId()) || incomingIds.contains(f.getId())) {
                continue;
            }
            ObjectNode node = storedRawById.get(f.getId());
            if (node == null) {
                continue;
            }
            node = node.deepCopy();
            node.put("removed", true);
            ExtTableField.ExtTableFieldMapping mapping = f.getSettings() == null ? null : f.getSettings().getMapping();
            boolean ownColumn = mapping != null && !(useExistsForm && mapping.isUseExistsTable());
            if (ownColumn && node.path("settings").get("mapping") instanceof ObjectNode m) {
                // 删除的字段：列重命名为字段 ID，保留历史数据，原列名可再次使用
                if (StringUtils.equals(f.getType(), "dateRange")) {
                    mapping.setOldColumnName1(mapping.getColumnName1());
                    mapping.setOldColumnName2(mapping.getColumnName2());
                    mapping.setColumnName1(deletedName(f.getId(), "_1"));
                    mapping.setColumnName2(deletedName(f.getId(), "_2"));
                    m.put("columnName1", mapping.getColumnName1());
                    m.put("columnName2", mapping.getColumnName2());
                } else {
                    mapping.setOldColumnName(mapping.getColumnName());
                    mapping.setColumnName(deletedName(f.getId(), ""));
                    m.put("columnName", mapping.getColumnName());
                }
                if (mapping.getType() == null) {
                    mapping.setType(io.dataease.ee.datafilling.ddl.DfDDLSupport.inferType(f));
                }
                toRename.add(f);
            }
            result.add(node);
        }
        // 已删除的历史字段原样保留
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).isRemoved()) {
                result.add(storedRaw.get(i));
            }
        }

        List<ExtTableField> active = new ArrayList<>(kept);
        active.addAll(toCreate);
        String pk = StringUtils.defaultIfBlank(form.getPrimaryKey(), DfConstants.DEFAULT_PK);
        checkColumns(active, Collections.emptyList(), pk);

        List<ExtIndexField> storedIndexes = DfJson.indexes(form.getTableIndexes());
        boolean createIndex = Boolean.TRUE.equals(form.getCreateIndex()) || Boolean.TRUE.equals(dto.getCreateIndex());
        List<ExtIndexField> incomingIndexes = Boolean.TRUE.equals(dto.getCreateIndex()) ? DfJson.indexes(dto.getTableIndexes()) : storedIndexes;
        Set<String> storedIndexNames = new HashSet<>();
        storedIndexes.forEach(ix -> storedIndexNames.add(ix.getName()));
        Set<String> incomingIndexNames = new HashSet<>();
        incomingIndexes.forEach(ix -> incomingIndexNames.add(ix.getName()));
        List<ExtIndexField> newIndexes = incomingIndexes.stream().filter(ix -> !storedIndexNames.contains(ix.getName())).toList();
        List<ExtIndexField> dropIndexes = storedIndexes.stream().filter(ix -> !incomingIndexNames.contains(ix.getName())).toList();

        if (!toCreate.isEmpty() || !toRename.isEmpty() || !newIndexes.isEmpty() || !dropIndexes.isEmpty()) {
            try (DfSession session = jdbcSupport.open(form.getDatasource())) {
                String table = form.getTableName();
                Map<String, Integer> existing = session.columnTypes(table);
                List<ExtTableField> create = new ArrayList<>();
                for (ExtTableField f : toCreate) {
                    if (useExistsForm && f.getSettings().getMapping().isUseExistsTable()) {
                        for (String col : columnNames(List.of(f))) {
                            if (!existing.containsKey(col.toLowerCase())) {
                                DEException.throwException(Translator.get("ee_df_column_not_exists", col));
                            }
                        }
                    } else {
                        for (String col : columnNames(List.of(f))) {
                            if (existing.containsKey(col.toLowerCase())) {
                                DEException.throwException(Translator.get("ee_df_column_duplicate", col));
                            }
                        }
                        create.add(f);
                    }
                }
                if (!toRename.isEmpty() || !create.isEmpty()) {
                    ExtDDLProvider ddl = jdbcSupport.requireDdl(session);
                    if (!dropIndexes.isEmpty()) {
                        dropIndexes(session, ddl, table, dropIndexes);
                    }
                    if (!toRename.isEmpty()) {
                        session.execute(ddl.addTableColumnSql(table, new ArrayList<>(), toRename));
                    }
                    if (!create.isEmpty()) {
                        session.execute(ddl.addTableColumnSql(table, create, new ArrayList<>()));
                    }
                } else if (!dropIndexes.isEmpty() && session.ddl() != null) {
                    dropIndexes(session, session.ddl(), table, dropIndexes);
                }
                if (!newIndexes.isEmpty() && session.ddl() != null) {
                    List<ExtTableField> all = new ArrayList<>(active);
                    all.addAll(toRename);
                    for (String s : session.ddl().createTableIndexSql(table, resolveIndexes(newIndexes, all))) {
                        session.execute(s);
                    }
                }
            }
        }
        form.setName(name);
        form.setPid(pid);
        form.setForms(DfJson.write(result));
        form.setCreateIndex(createIndex);
        form.setTableIndexes(DfJson.write(stripOld(incomingIndexes)));
        form.setUpdateBy(V3UserUtil.getUid());
        form.setUpdateTime(System.currentTimeMillis());
        formRepository.save(form);
        treeSupport.update(EeConstants.RT_DATA_FILLING, form.getId(), name, pid, true, null, null);
        return toDTO(form);
    }

    private void dropIndexes(DfSession session, ExtDDLProvider ddl, String table, List<ExtIndexField> indexes) {
        for (String s : ddl.dropTableIndexSql(table, indexes)) {
            try {
                session.execute(s);
            } catch (Exception e) {
                LogUtil.warn("drop index failed: " + e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> stripOld(List<ExtIndexField> indexes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExtIndexField ix : indexes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ix.getName());
            List<Map<String, Object>> cols = new ArrayList<>();
            if (ix.getColumns() != null) {
                for (ExtIndexField.ColumnSetting c : ix.getColumns()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("column", c.getColumn());
                    cm.put("order", c.getOrder());
                    cols.add(cm);
                }
            }
            m.put("columns", cols);
            list.add(m);
        }
        return list;
    }

    private String deletedName(String id, String suffix) {
        String base = StringUtils.replace(id, "-", "_");
        return StringUtils.left("del_" + base, 60 - suffix.length()) + suffix;
    }

    /**
     * 新字段补全 ID 与列类型
     */
    private void normalizeNewItems(ArrayNode raw, List<ExtTableField> fields) {
        int index = 0;
        for (JsonNode n : raw) {
            if (!(n instanceof ObjectNode node) || node.path("removed").asBoolean(false)) {
                continue;
            }
            if (StringUtils.isBlank(node.path("id").asText(null))) {
                node.put("id", UUID.randomUUID().toString());
            }
            node.remove("old");
            ExtTableField f = DfJson.fields("[" + DfJson.write(node) + "]").getFirst();
            ensureMappingType(node, f);
            if (index < fields.size()) {
                fields.set(index, DfJson.fields("[" + DfJson.write(node) + "]").getFirst());
            }
            index++;
        }
    }

    private void ensureMappingType(ObjectNode node, ExtTableField f) {
        if (f.getSettings() == null || f.getSettings().getMapping() == null) {
            DEException.throwException(Translator.get("ee_df_column_required"));
        }
        if (f.getSettings().getMapping().getType() == null && node.path("settings").get("mapping") instanceof ObjectNode m) {
            m.put("type", io.dataease.ee.datafilling.ddl.DfDDLSupport.inferType(f).name());
        }
    }

    public DataFillingDTO rename(DataFillingDTO dto) {
        DfForm form = access.form(dto.getId());
        access.require(form.getId(), EeConstants.WEIGHT_MANAGE);
        String name = checkName(dto.getName());
        checkDuplicateName(form.getId(), form.getPid(), name, form.getNodeType());
        form.setName(name);
        form.setUpdateBy(V3UserUtil.getUid());
        form.setUpdateTime(System.currentTimeMillis());
        formRepository.save(form);
        treeSupport.update(EeConstants.RT_DATA_FILLING, form.getId(), name, form.getPid(),
                StringUtils.equals(form.getNodeType(), DfConstants.NODE_FORM), null, null);
        return toDTO(form);
    }

    public DataFillingDTO move(DataFillingDTO dto) {
        DfForm form = access.form(dto.getId());
        access.require(form.getId(), EeConstants.WEIGHT_MANAGE);
        Long pid = ObjectUtils.defaultIfNull(dto.getPid(), 0L);
        checkMoveTarget(form, pid);
        String name = StringUtils.isBlank(dto.getName()) ? form.getName() : checkName(dto.getName());
        checkDuplicateName(form.getId(), pid, name, form.getNodeType());
        form.setPid(pid);
        form.setName(name);
        form.setUpdateBy(V3UserUtil.getUid());
        form.setUpdateTime(System.currentTimeMillis());
        formRepository.save(form);
        treeSupport.update(EeConstants.RT_DATA_FILLING, form.getId(), name, pid,
                StringUtils.equals(form.getNodeType(), DfConstants.NODE_FORM), null, null);
        return toDTO(form);
    }

    private void checkMoveTarget(DfForm form, Long pid) {
        checkParent(pid);
        access.require(pid, EeConstants.WEIGHT_MANAGE);
        if (pid != 0L && (Objects.equals(pid, form.getId()) || descendants(form.getId()).contains(pid))) {
            DEException.throwException(Translator.get("ee_df_move_invalid"));
        }
    }

    @Transactional
    public void delete(ResourceDeleteRequest request) {
        if (request == null || request.getId() == null) {
            return;
        }
        DfForm form = access.form(request.getId());
        access.require(form.getId(), EeConstants.WEIGHT_MANAGE);
        List<Long> ids = new ArrayList<>(descendants(form.getId()));
        ids.add(form.getId());
        List<DfForm> forms = formRepository.findAllById(ids);
        for (DfForm f : forms) {
            if (StringUtils.equals(f.getNodeType(), DfConstants.NODE_FORM)) {
                taskManage.deleteByForm(f.getId());
                dataManage.deleteLogsByForm(f.getId());
            }
        }
        List<OwnedTable> tables = ownedTables(forms);
        formRepository.deleteAllById(ids);
        treeSupport.remove(form.getId());
        dropUnusedTablesAfterCommit(tables);
    }

    /**
     * 表单自建的数据表（非绑定已有表）
     */
    private record OwnedTable(Long datasource, String table) {
    }

    private List<OwnedTable> ownedTables(List<DfForm> forms) {
        List<OwnedTable> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DfForm f : forms) {
            if (!StringUtils.equals(f.getNodeType(), DfConstants.NODE_FORM) || Boolean.TRUE.equals(f.getUseExistsTable())
                    || f.getDatasource() == null || StringUtils.isBlank(f.getTableName())) {
                continue;
            }
            if (seen.add(f.getDatasource() + "/" + f.getTableName().toLowerCase())) {
                result.add(new OwnedTable(f.getDatasource(), f.getTableName()));
            }
        }
        return result;
    }

    /**
     * 删除表单的事务提交后清理其自建的数据表
     */
    private void dropUnusedTablesAfterCommit(List<OwnedTable> tables) {
        if (tables.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dropUnusedTables(tables);
                }
            });
        } else {
            dropUnusedTables(tables);
        }
    }

    /**
     * 自建表在没有其他表单引用且没有数据时删除，否则保留（只记录日志），任何异常都不影响删除表单本身
     */
    private void dropUnusedTables(List<OwnedTable> tables) {
        QDfForm q = QDfForm.dfForm;
        for (OwnedTable t : tables) {
            String target = "datasource " + t.datasource() + ", table " + t.table();
            try {
                boolean referenced = queryFactory.selectOne().from(q)
                        .where(q.nodeType.eq(DfConstants.NODE_FORM), q.datasource.eq(t.datasource()), q.tableName.equalsIgnoreCase(t.table()))
                        .fetchFirst() != null;
                if (referenced) {
                    LogUtil.info("data filling: keep table of deleted form (still used by another form): " + target);
                    continue;
                }
                try (DfSession session = jdbcSupport.open(t.datasource())) {
                    if (!session.tableExists(t.table())) {
                        continue;
                    }
                    long rows = session.rowCount(t.table());
                    if (rows > 0) {
                        LogUtil.info("data filling: keep table of deleted form (contains " + rows + " rows): " + target);
                        continue;
                    }
                    if (session.ddl() == null) {
                        LogUtil.info("data filling: keep table of deleted form (no DDL provider): " + target);
                        continue;
                    }
                    session.execute(session.ddl().dropTableSql(t.table()));
                    LogUtil.info("data filling: dropped empty table of deleted form: " + target);
                }
            } catch (Exception e) {
                LogUtil.warn("data filling: keep table of deleted form (" + e.getMessage() + "): " + target);
            }
        }
    }

    /**
     * 删除组织等场景下的强制删除（不校验权限）
     */
    @Transactional
    public void forceDelete(Long id) {
        DfForm form = formRepository.findById(id).orElse(null);
        if (form == null) {
            return;
        }
        List<Long> ids = new ArrayList<>(descendants(id));
        ids.add(id);
        List<DfForm> forms = formRepository.findAllById(ids);
        for (DfForm f : forms) {
            if (StringUtils.equals(f.getNodeType(), DfConstants.NODE_FORM)) {
                taskManage.deleteByForm(f.getId());
                dataManage.deleteLogsByForm(f.getId());
            }
        }
        List<OwnedTable> tables = ownedTables(forms);
        formRepository.deleteAllById(ids);
        dropUnusedTablesAfterCommit(tables);
    }

    public Set<Long> descendants(Long id) {
        QDfForm q = QDfForm.dfForm;
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(id);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            for (Long kid : queryFactory.select(q.id).from(q).where(q.pid.eq(current)).fetch()) {
                if (result.add(kid)) {
                    stack.push(kid);
                }
            }
        }
        return result;
    }

    public DataFillingDTO get(Long id) {
        return toDTO(access.form(id));
    }

    public DataFillingDTO findDTO(Long id) {
        DfForm form = id == null ? null : formRepository.findById(id).orElse(null);
        return form == null ? null : toDTO(form);
    }

    public void fullName(Long id, List<String> names) {
        Set<Long> visited = new HashSet<>();
        Long current = id;
        while (current != null && current != 0L && visited.add(current)) {
            DfForm form = formRepository.findById(current).orElse(null);
            if (form == null) {
                break;
            }
            names.add(form.getName());
            current = form.getPid();
        }
    }

    public DataFillingDTO toDTO(DfForm form) {
        DataFillingDTO dto = new DataFillingDTO();
        dto.setId(form.getId());
        dto.setPid(form.getPid());
        dto.setName(form.getName());
        dto.setNodeType(form.getNodeType());
        dto.setTableName(form.getTableName());
        dto.setDatasource(form.getDatasource());
        dto.setForms(form.getForms());
        dto.setCreateIndex(form.getCreateIndex());
        dto.setTableIndexes(StringUtils.defaultIfBlank(form.getTableIndexes(), "[]"));
        dto.setCreateBy(form.getCreateBy());
        dto.setCreateTime(form.getCreateTime());
        dto.setUpdateBy(form.getUpdateBy());
        dto.setUpdateTime(form.getUpdateTime());
        dto.setUseExistsTable(Boolean.TRUE.equals(form.getUseExistsTable()));
        dto.setOrgRoot(false);
        Map<Long, String> names = users.names(Arrays.asList(form.getCreateBy(), form.getUpdateBy()));
        dto.setCreator(names.get(form.getCreateBy()));
        dto.setUpdater(names.get(form.getUpdateBy()));
        if (form.getDatasource() != null) {
            dto.setDatasourceName(datasourceName(form.getDatasource()));
        }
        return dto;
    }

    private String datasourceName(Long dsId) {
        if (dsId == DfConstants.ENGINE_DS_ID) {
            return Translator.get("ee_df_engine_name");
        }
        return datasourceRepository.findById(dsId).map(CoreDatasource::getName).orElse(null);
    }

    // ------------------------------------------------------------------ datasource

    /**
     * 可用数据源；forStorage 为 true 时只返回可作为填报存储的数据源（含内置引擎）
     */
    public List<SimpleDatasourceDTO> datasourceList(boolean forStorage) {
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        boolean admin = V3UserUtil.isSysAdmin(uid);
        Map<Long, Perm> perms = admin ? Collections.emptyMap() : permissionManage.effective(uid, oid, EeConstants.RT_DATASOURCE);
        Map<String, String> pluginNames = pluginNames();
        List<SimpleDatasourceDTO> result = new ArrayList<>();
        if (forStorage) {
            CoreDeEngine engine = engineRepository.findAll().stream().findFirst().orElse(null);
            if (engine != null && Boolean.TRUE.equals(engine.getEnableDataFill()) && jdbcSupport.supportDdl(engine.getType())) {
                SimpleDatasourceDTO dto = new SimpleDatasourceDTO();
                dto.setId(DfConstants.ENGINE_DS_ID);
                dto.setPid(0L);
                dto.setName(Translator.get("ee_df_engine_name"));
                dto.setType(engine.getType());
                dto.setTypeAlias(typeAlias(engine.getType(), pluginNames));
                dto.setStatus(StringUtils.equalsIgnoreCase(engine.getStatus(), "Error") ? "Error" : "Success");
                dto.setEnableDataFill(true);
                result.add(dto);
            }
        }
        for (CoreDatasource ds : datasourceRepository.findAll()) {
            if (StringUtils.equalsAnyIgnoreCase(ds.getType(), "folder", "es")) {
                continue;
            }
            if (!admin) {
                Perm perm = perms.get(ds.getId());
                if (perm == null || !perm.satisfies(EeConstants.WEIGHT_READ)) {
                    continue;
                }
            }
            if (forStorage && !(Boolean.TRUE.equals(ds.getEnableDataFill()) && jdbcSupport.supportDdl(ds.getType()))) {
                continue;
            }
            SimpleDatasourceDTO dto = new SimpleDatasourceDTO();
            dto.setId(ds.getId());
            dto.setPid(ds.getPid());
            dto.setName(ds.getName());
            dto.setType(ds.getType());
            dto.setTypeAlias(typeAlias(ds.getType(), pluginNames));
            dto.setStatus(StringUtils.equalsIgnoreCase(ds.getStatus(), "Error") ? "Error" : "Success");
            dto.setEnableDataFill(Boolean.TRUE.equals(ds.getEnableDataFill()));
            result.add(dto);
        }
        return result;
    }

    private Map<String, String> pluginNames() {
        Map<String, String> map = new HashMap<>();
        try {
            for (XpackPluginsDatasourceVO vo : ProviderFactory.getDsConfigList()) {
                map.put(vo.getType(), vo.getName());
            }
        } catch (Exception ignore) {
        }
        return map;
    }

    private String typeAlias(String type, Map<String, String> pluginNames) {
        for (DatasourceConfiguration.DatasourceType t : DatasourceConfiguration.DatasourceType.values()) {
            if (StringUtils.equals(t.getType(), type)) {
                return t.getName();
            }
        }
        return pluginNames.getOrDefault(type, type);
    }

    /**
     * 校验作为填报存储的数据源
     */
    public CoreDatasource checkStorageDatasource(Long dsId) {
        if (dsId == null) {
            DEException.throwException(Translator.get("ee_df_datasource_not_exists"));
        }
        if (dsId == DfConstants.ENGINE_DS_ID) {
            CoreDeEngine engine = engineRepository.findAll().stream().findFirst().orElse(null);
            if (engine == null || !Boolean.TRUE.equals(engine.getEnableDataFill())) {
                DEException.throwException(Translator.get("ee_df_datasource_not_enabled"));
            }
            return jdbcSupport.datasource(dsId);
        }
        CoreDatasource ds = jdbcSupport.datasource(dsId);
        if (!Boolean.TRUE.equals(ds.getEnableDataFill())) {
            DEException.throwException(Translator.get("ee_df_datasource_not_enabled"));
        }
        if (!V3UserUtil.isSysAdmin()) {
            Perm perm = permissionManage.effective(V3UserUtil.getUid(), V3UserUtil.getOid(), EeConstants.RT_DATASOURCE, dsId);
            if (!perm.satisfies(EeConstants.WEIGHT_READ)) {
                access.deny();
            }
        }
        if (!jdbcSupport.supportDdl(ds.getType())) {
            DEException.throwException(Translator.get("ee_df_datasource_not_support"));
        }
        return ds;
    }

    public List<DatasetTableDTO> builtInTables() {
        try (DfSession session = jdbcSupport.open(DfConstants.ENGINE_DS_ID)) {
            List<DatasetTableDTO> result = new ArrayList<>();
            for (String t : session.tables()) {
                DatasetTableDTO dto = new DatasetTableDTO();
                dto.setTableName(t);
                dto.setName(t);
                dto.setType("db");
                dto.setDatasourceId(DfConstants.ENGINE_DS_ID);
                result.add(dto);
            }
            return result;
        }
    }

    public List<TableField> builtInTableField(String table) {
        if (StringUtils.isBlank(table)) {
            return new ArrayList<>();
        }
        try (DfSession session = jdbcSupport.open(DfConstants.ENGINE_DS_ID)) {
            return session.columns(table);
        }
    }

    // ------------------------------------------------------------------ validation

    private String checkName(String name) {
        String trimmed = StringUtils.trimToEmpty(name);
        if (trimmed.isEmpty()) {
            DEException.throwException(Translator.get("ee_df_name_required"));
        }
        if (trimmed.length() > NAME_MAX) {
            DEException.throwException(Translator.get("ee_df_name_too_long"));
        }
        return trimmed;
    }

    private void checkParent(Long pid) {
        if (pid == null || pid == 0L) {
            return;
        }
        DfForm parent = formRepository.findById(pid).orElse(null);
        if (parent == null || !StringUtils.equals(parent.getNodeType(), DfConstants.NODE_FOLDER)
                || !Objects.equals(parent.getOrgId(), V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_df_folder_invalid"));
        }
    }

    private void checkDuplicateName(Long selfId, Long pid, String name, String nodeType) {
        QDfForm q = QDfForm.dfForm;
        var query = queryFactory.select(q.id).from(q).where(q.pid.eq(pid), q.name.eq(name), q.nodeType.eq(nodeType),
                q.orgId.eq(V3UserUtil.getOid()));
        if (selfId != null) {
            query.where(q.id.ne(selfId));
        }
        if (query.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_df_name_duplicate", name));
        }
    }

    private String checkTableName(String table) {
        String t = StringUtils.trimToEmpty(table);
        if (t.isEmpty()) {
            DEException.throwException(Translator.get("ee_df_table_required"));
        }
        if (!validIdentifier(t)) {
            DEException.throwException(Translator.get("ee_df_table_invalid", t));
        }
        return t;
    }

    private boolean validIdentifier(String name) {
        return name.length() <= 64 && !StringUtils.containsAny(name, '`', '"', '[', ']', ';', '\'', '\\', '\n', '\r', '\t')
                && !StringUtils.startsWith(name, " ") && !StringUtils.endsWith(name, " ");
    }

    public static List<String> columnNames(List<ExtTableField> fields) {
        List<String> names = new ArrayList<>();
        for (ExtTableField f : fields) {
            ExtTableField.ExtTableFieldMapping m = f.getSettings() == null ? null : f.getSettings().getMapping();
            if (m == null) {
                names.add(null);
                continue;
            }
            if (StringUtils.equals(f.getType(), "dateRange")) {
                names.add(m.getColumnName1());
                names.add(m.getColumnName2());
            } else {
                names.add(m.getColumnName());
            }
        }
        return names;
    }

    private void checkColumns(List<ExtTableField> fields, List<String> extra, String pk) {
        Set<String> seen = new HashSet<>();
        extra.forEach(e -> seen.add(e.toLowerCase()));
        for (String col : columnNames(fields)) {
            if (StringUtils.isBlank(col)) {
                DEException.throwException(Translator.get("ee_df_column_required"));
            }
            if (!validIdentifier(col)) {
                DEException.throwException(Translator.get("ee_df_column_invalid", col));
            }
            if (StringUtils.equalsIgnoreCase(col, pk)) {
                DEException.throwException(Translator.get("ee_df_column_reserved", col));
            }
            if (!seen.add(col.toLowerCase())) {
                DEException.throwException(Translator.get("ee_df_column_duplicate", col));
            }
        }
    }

    /**
     * 索引列（字段 ID / ID_1 / ID_2）转换为实际列名
     */
    private List<ExtIndexField> resolveIndexes(List<ExtIndexField> indexes, List<ExtTableField> fields) {
        Map<String, String> columns = new HashMap<>();
        for (ExtTableField f : fields) {
            ExtTableField.ExtTableFieldMapping m = f.getSettings() == null ? null : f.getSettings().getMapping();
            if (m == null || f.getId() == null) {
                continue;
            }
            if (StringUtils.equals(f.getType(), "dateRange")) {
                columns.put(f.getId() + "_1", m.getColumnName1());
                columns.put(f.getId() + "_2", m.getColumnName2());
            } else {
                columns.put(f.getId(), m.getColumnName());
            }
        }
        List<ExtIndexField> result = new ArrayList<>();
        for (ExtIndexField ix : indexes) {
            if (StringUtils.isBlank(ix.getName()) || !validIdentifier(ix.getName()) || CollectionUtils.isEmpty(ix.getColumns())) {
                DEException.throwException(Translator.get("ee_df_index_invalid", StringUtils.defaultString(ix.getName())));
            }
            List<ExtIndexField.ColumnSetting> cols = new ArrayList<>();
            for (ExtIndexField.ColumnSetting c : ix.getColumns()) {
                String col = columns.get(c.getColumn());
                if (col == null) {
                    DEException.throwException(Translator.get("ee_df_index_invalid", ix.getName()));
                }
                cols.add(ExtIndexField.ColumnSetting.builder().column(col).order(c.getOrder()).build());
            }
            result.add(ExtIndexField.builder().name(ix.getName()).columns(cols).build());
        }
        return result;
    }
}
