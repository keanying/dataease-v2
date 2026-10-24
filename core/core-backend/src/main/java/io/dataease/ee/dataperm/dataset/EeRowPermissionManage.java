package io.dataease.ee.dataperm.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.dataset.dto.DataSetRowPermissionsTreeDTO;
import io.dataease.api.permissions.dataset.dto.DatasetRowPermissionsTreeRequest;
import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.dao.entity.DatasetRowPermission;
import io.dataease.ee.dataperm.dao.entity.QDatasetRowPermission;
import io.dataease.ee.dataperm.dao.repo.DatasetRowPermissionRepository;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeItem;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeObj;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据集行权限：规则的增删改查，以及供核心取数链路（{@code PermissionManage}）使用的规则查询。
 *
 * <p>规则按授权对象分为用户、角色、系统变量（sysParams）三类；核心将命中的多条规则以 OR 合并，
 * 系统管理员不受限制，白名单中的用户/角色跳过该规则。</p>
 */
@Component
public class EeRowPermissionManage {

    private static final Set<String> LOGICS = Set.of("and", "or");

    private final JPAQueryFactory queryFactory;

    private final DatasetRowPermissionRepository repository;

    private final EeDatapermSupport support;

    private final DatasetTableFieldManage fieldManage;

    public EeRowPermissionManage(JPAQueryFactory queryFactory, DatasetRowPermissionRepository repository,
                                 EeDatapermSupport support, @Lazy DatasetTableFieldManage fieldManage) {
        this.queryFactory = queryFactory;
        this.repository = repository;
        this.support = support;
        this.fieldManage = fieldManage;
    }

    // ------------------------------------------------------------------ 管理接口

    public PageResult<DataSetRowPermissionsTreeDTO> page(Long datasetId, int goPage, int pageSize) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        var query = queryFactory.selectFrom(q).where(q.datasetId.eq(datasetId));
        long total = query.clone().fetchCount();
        List<DatasetRowPermission> rows = query.orderBy(q.updateTime.desc(), q.id.desc())
                .offset((long) (page - 1) * size).limit(size).fetch();
        return new PageResult<>(enrich(rows), total, PageRequest.of(page - 1, size));
    }

    public DatasetRowPermission get(Long id) {
        return id == null ? null : repository.findById(id).orElse(null);
    }

    public DatasetRowPermission require(Long id) {
        DatasetRowPermission row = get(id);
        if (row == null) {
            DEException.throwException(Translator.get("ee_dataperm_rule_not_exist"));
        }
        return row;
    }

    @Transactional
    public void save(DataSetRowPermissionsTreeDTO dto, Long datasetId) {
        DatasetRowPermission existing = dto.getId() == null ? null : require(dto.getId());
        String type = EeDatapermSupport.normalizeTargetType(dto.getAuthTargetType(), true);
        Long targetId = null;
        if (!DatapermConstants.TARGET_SYS_PARAMS.equals(type)) {
            targetId = dto.getAuthTargetId();
            support.checkTarget(datasetId, type, targetId);
            QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
            var duplicate = queryFactory.select(q.id).from(q)
                    .where(q.datasetId.eq(datasetId), q.authTargetType.eq(type), q.authTargetId.eq(targetId));
            if (existing != null) {
                duplicate.where(q.id.ne(existing.getId()));
            }
            if (duplicate.fetchFirst() != null) {
                DEException.throwException(Translator.get("ee_dataperm_target_exists"));
            }
        }
        // 用户规则没有白名单；角色白名单只用于系统变量规则
        String whiteListUser = DatapermConstants.TARGET_USER.equals(type) ? DatapermConstants.EMPTY_JSON_ARRAY
                : EeDatapermSupport.normalizeIdList(dto.getWhiteListUser());
        String whiteListRole = DatapermConstants.TARGET_SYS_PARAMS.equals(type)
                ? EeDatapermSupport.normalizeIdList(dto.getWhiteListRole()) : DatapermConstants.EMPTY_JSON_ARRAY;
        String expressionTree = normalizeExpression(dto.getExpressionTree(), datasetId);
        boolean enable = dto.getEnable() == null || dto.getEnable();
        long now = System.currentTimeMillis();
        if (existing == null) {
            DatasetRowPermission row = new DatasetRowPermission();
            row.setId(IDUtils.snowID());
            row.setDatasetId(datasetId);
            row.setAuthTargetType(type);
            row.setAuthTargetId(targetId);
            row.setEnable(enable);
            row.setExpressionTree(expressionTree);
            row.setWhiteListUser(whiteListUser);
            row.setWhiteListRole(whiteListRole);
            row.setWhiteListDept(DatapermConstants.EMPTY_JSON_ARRAY);
            row.setCreateBy(V3UserUtil.getUid());
            row.setUpdateTime(now);
            repository.save(row);
            return;
        }
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        var update = queryFactory.update(q).where(q.id.eq(existing.getId()))
                .set(q.authTargetType, type)
                .set(q.enable, enable)
                .set(q.expressionTree, expressionTree)
                .set(q.whiteListUser, whiteListUser)
                .set(q.whiteListRole, whiteListRole)
                .set(q.updateTime, now);
        if (targetId == null) {
            update.setNull(q.authTargetId);
        } else {
            update.set(q.authTargetId, targetId);
        }
        update.execute();
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public DataSetRowPermissionsTreeDTO info(Long datasetId, String authTargetType, Long authTargetId) {
        if (datasetId == null || StringUtils.isBlank(authTargetType)) {
            return null;
        }
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        var query = queryFactory.selectFrom(q).where(q.datasetId.eq(datasetId), q.authTargetType.eq(authTargetType));
        query.where(authTargetId == null ? q.authTargetId.isNull() : q.authTargetId.eq(authTargetId));
        DatasetRowPermission row = query.orderBy(q.updateTime.desc()).fetchFirst();
        return row == null ? null : enrich(List.of(row)).getFirst();
    }

    // ------------------------------------------------------------------ 核心取数链路

    /**
     * 按数据集、启用状态、授权对象类型与授权对象查询规则，并解析 tree（不含 field，由核心填充）
     */
    public List<DataSetRowPermissionsTreeDTO> list(DatasetRowPermissionsTreeRequest request) {
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        List<BooleanExpression> conditions = new ArrayList<>();
        if (request != null) {
            if (request.getDatasetId() != null) {
                conditions.add(q.datasetId.eq(request.getDatasetId()));
            }
            if (request.getEnable() != null) {
                conditions.add(request.getEnable() ? q.enable.isTrue() : q.enable.isFalse().or(q.enable.isNull()));
            }
            if (StringUtils.isNotBlank(request.getAuthTargetType())) {
                conditions.add(q.authTargetType.eq(request.getAuthTargetType()));
            }
            if (request.getAuthTargetIds() != null) {
                List<Long> ids = request.getAuthTargetIds().stream().filter(Objects::nonNull).toList();
                if (ids.isEmpty()) {
                    return new ArrayList<>();
                }
                conditions.add(q.authTargetId.in(ids));
            }
            if (request.getAuthTargetId() != null) {
                conditions.add(q.authTargetId.eq(request.getAuthTargetId()));
            }
        }
        List<DatasetRowPermission> rows = queryFactory.selectFrom(q).where(conditions.toArray(new BooleanExpression[0]))
                .orderBy(q.updateTime.desc(), q.id.desc()).fetch();
        List<DataSetRowPermissionsTreeDTO> result = new ArrayList<>();
        for (DatasetRowPermission row : rows) {
            DataSetRowPermissionsTreeDTO dto = toDto(row);
            dto.setTree(parseTree(row.getExpressionTree()));
            result.add(dto);
        }
        return result;
    }

    public List<DatasetRowPermission> listByDatasets(Collection<Long> datasetIds) {
        if (CollectionUtils.isEmpty(datasetIds)) {
            return new ArrayList<>();
        }
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        return queryFactory.selectFrom(q).where(q.datasetId.in(datasetIds)).orderBy(q.updateTime.desc()).fetch();
    }

    public List<Long> roleTargetsOf(Long datasetId) {
        QDatasetRowPermission q = QDatasetRowPermission.datasetRowPermission;
        return queryFactory.select(q.authTargetId).distinct().from(q)
                .where(q.datasetId.eq(datasetId), q.authTargetType.eq(DatapermConstants.TARGET_ROLE)).fetch();
    }

    // ------------------------------------------------------------------ 转换

    private DataSetRowPermissionsTreeDTO toDto(DatasetRowPermission row) {
        DataSetRowPermissionsTreeDTO dto = new DataSetRowPermissionsTreeDTO();
        dto.setId(row.getId());
        dto.setDatasetId(row.getDatasetId());
        dto.setAuthTargetType(row.getAuthTargetType());
        dto.setAuthTargetId(row.getAuthTargetId());
        dto.setEnable(Boolean.TRUE.equals(row.getEnable()));
        dto.setExpressionTree(row.getExpressionTree());
        dto.setWhiteListUser(EeDatapermSupport.defaultIds(row.getWhiteListUser()));
        dto.setWhiteListRole(EeDatapermSupport.defaultIds(row.getWhiteListRole()));
        dto.setWhiteListDept(EeDatapermSupport.defaultIds(row.getWhiteListDept()));
        dto.setUpdateTime(row.getUpdateTime());
        return dto;
    }

    /**
     * 管理界面使用的完整结构：授权对象名称、白名单名称、tree 中填充 field 且 enumValue 为数组
     */
    private List<DataSetRowPermissionsTreeDTO> enrich(List<DatasetRowPermission> rows) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> roleIds = new HashSet<>();
        Set<Long> datasetIds = new HashSet<>();
        for (DatasetRowPermission row : rows) {
            datasetIds.add(row.getDatasetId());
            if (DatapermConstants.TARGET_USER.equals(row.getAuthTargetType())) {
                userIds.add(row.getAuthTargetId());
            } else if (DatapermConstants.TARGET_ROLE.equals(row.getAuthTargetType())) {
                roleIds.add(row.getAuthTargetId());
            }
        }
        Map<Long, String> userNames = support.userNames(userIds);
        Map<Long, String> roleNames = support.roleNames(roleIds);
        Map<Long, String> datasetNames = new HashMap<>();
        datasetIds.forEach(id -> datasetNames.put(id, support.datasetName(id)));
        List<DataSetRowPermissionsTreeDTO> result = new ArrayList<>();
        for (DatasetRowPermission row : rows) {
            DataSetRowPermissionsTreeDTO dto = toDto(row);
            dto.setDatasetName(datasetNames.get(row.getDatasetId()));
            if (DatapermConstants.TARGET_USER.equals(row.getAuthTargetType())) {
                dto.setAuthTargetName(userNames.get(row.getAuthTargetId()));
            } else if (DatapermConstants.TARGET_ROLE.equals(row.getAuthTargetType())) {
                dto.setAuthTargetName(roleNames.get(row.getAuthTargetId()));
            }
            dto.setWhiteListUsers(support.userOptions(EeDatapermSupport.parseIdList(dto.getWhiteListUser())));
            dto.setWhiteListRoles(support.roleOptions(EeDatapermSupport.parseIdList(dto.getWhiteListRole())));
            DatasetRowPermissionsTreeObj tree = parseTree(row.getExpressionTree());
            fillFields(tree);
            dto.setTree(tree);
            result.add(dto);
        }
        return result;
    }

    static DatasetRowPermissionsTreeObj parseTree(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            DatasetRowPermissionsTreeObj tree = EeDatapermSupport.MAPPER.readValue(json, DatasetRowPermissionsTreeObj.class);
            normalizeTree(tree);
            return tree;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static void normalizeTree(DatasetRowPermissionsTreeObj tree) {
        if (tree == null) {
            return;
        }
        if (tree.getItems() == null) {
            tree.setItems(new ArrayList<>());
        }
        for (DatasetRowPermissionsTreeItem item : tree.getItems()) {
            if (item == null) {
                continue;
            }
            if (item.getEnumValue() == null) {
                item.setEnumValue(new ArrayList<>());
            }
            normalizeTree(item.getSubTree());
        }
    }

    private void fillFields(DatasetRowPermissionsTreeObj tree) {
        Set<Long> fieldIds = new HashSet<>();
        collectFieldIds(tree, fieldIds);
        if (fieldIds.isEmpty()) {
            return;
        }
        Map<Long, DatasetTableFieldDTO> fields = fieldManage.selectByFieldIds(new ArrayList<>(fieldIds)).stream()
                .collect(Collectors.toMap(DatasetTableFieldDTO::getId, field -> field, (a, b) -> a));
        applyFields(tree, fields);
    }

    private static void collectFieldIds(DatasetRowPermissionsTreeObj tree, Set<Long> fieldIds) {
        if (tree == null || tree.getItems() == null) {
            return;
        }
        for (DatasetRowPermissionsTreeItem item : tree.getItems()) {
            if (item == null) {
                continue;
            }
            if (item.getFieldId() != null) {
                fieldIds.add(item.getFieldId());
            }
            collectFieldIds(item.getSubTree(), fieldIds);
        }
    }

    private static void applyFields(DatasetRowPermissionsTreeObj tree, Map<Long, DatasetTableFieldDTO> fields) {
        if (tree == null || tree.getItems() == null) {
            return;
        }
        for (DatasetRowPermissionsTreeItem item : tree.getItems()) {
            if (item == null) {
                continue;
            }
            if (item.getFieldId() != null) {
                item.setField(fields.get(item.getFieldId()));
            }
            applyFields(item.getSubTree(), fields);
        }
    }

    /**
     * 校验并规范化表达式树：logic 只能为 and/or，条件字段必须属于该数据集，去掉 field 对象，enumValue 补为数组
     */
    private String normalizeExpression(String json, Long datasetId) {
        DatasetRowPermissionsTreeObj tree = null;
        if (StringUtils.isNotBlank(json)) {
            try {
                tree = EeDatapermSupport.MAPPER.readValue(json, DatasetRowPermissionsTreeObj.class);
            } catch (JsonProcessingException e) {
                DEException.throwException(Translator.get("ee_dataperm_expression_invalid"));
            }
        }
        if (tree == null) {
            tree = new DatasetRowPermissionsTreeObj();
        }
        Set<Long> fieldIds = fieldManage.selectByDatasetGroupId(datasetId).stream().map(DatasetTableFieldDTO::getId).collect(Collectors.toSet());
        checkTree(tree, fieldIds);
        return EeDatapermSupport.toJson(tree);
    }

    private static void checkTree(DatasetRowPermissionsTreeObj tree, Set<Long> fieldIds) {
        String logic = StringUtils.lowerCase(StringUtils.trimToNull(tree.getLogic()));
        if (logic == null) {
            logic = "and";
        }
        if (!LOGICS.contains(logic)) {
            DEException.throwException(Translator.get("ee_dataperm_expression_invalid"));
        }
        tree.setLogic(logic);
        List<DatasetRowPermissionsTreeItem> items = new ArrayList<>();
        for (DatasetRowPermissionsTreeItem item : tree.getItems() == null ? List.<DatasetRowPermissionsTreeItem>of() : tree.getItems()) {
            if (item == null) {
                continue;
            }
            item.setField(null);
            if (item.getEnumValue() == null) {
                item.setEnumValue(new ArrayList<>());
            }
            if (StringUtils.equalsIgnoreCase(item.getType(), "tree")) {
                if (item.getSubTree() == null) {
                    DEException.throwException(Translator.get("ee_dataperm_expression_invalid"));
                }
                item.setType("tree");
                checkTree(item.getSubTree(), fieldIds);
            } else if (StringUtils.equalsIgnoreCase(item.getType(), "item")) {
                if (item.getFieldId() == null || !fieldIds.contains(item.getFieldId()) || StringUtils.isBlank(item.getTerm())) {
                    DEException.throwException(Translator.get("ee_dataperm_expression_invalid"));
                }
                item.setType("item");
                item.setSubTree(null);
            } else {
                DEException.throwException(Translator.get("ee_dataperm_expression_invalid"));
            }
            items.add(item);
        }
        tree.setItems(items);
    }
}
