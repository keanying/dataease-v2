package io.dataease.ee.dataperm.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.permissions.auth.vo.SubjectNode;
import io.dataease.api.permissions.dataset.dto.Item;
import io.dataease.api.permissions.role.vo.RoleVO;
import io.dataease.api.permissions.user.vo.UserFormVO;
import io.dataease.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupRepository;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.variable.EeSysVariableManage;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.repo.PerRoleRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

/**
 * 行列权限公共能力：数据集管理权限校验、授权对象（用户/角色）查询与校验、白名单 JSON 规范化。
 */
@Component
public class EeDatapermSupport {

    /**
     * 解析前端提交内容使用的严格 ObjectMapper（解析失败即报错，不吞异常）
     */
    static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<Object>> OBJECT_LIST = new TypeReference<>() {
    };

    private final EePermissionManage permissionManage;

    private final EeBusiResourceManage busiResourceManage;

    private final CoreDatasetGroupRepository datasetGroupRepository;

    private final PerUserRepository perUserRepository;

    private final PerRoleRepository perRoleRepository;

    private final EeRoleManage roleManage;

    private final EeOrgManage orgManage;

    private final EeSysVariableManage variableManage;

    public EeDatapermSupport(EePermissionManage permissionManage, EeBusiResourceManage busiResourceManage,
                             CoreDatasetGroupRepository datasetGroupRepository, PerUserRepository perUserRepository,
                             PerRoleRepository perRoleRepository, EeRoleManage roleManage, EeOrgManage orgManage,
                             EeSysVariableManage variableManage) {
        this.permissionManage = permissionManage;
        this.busiResourceManage = busiResourceManage;
        this.datasetGroupRepository = datasetGroupRepository;
        this.perUserRepository = perUserRepository;
        this.perRoleRepository = perRoleRepository;
        this.roleManage = roleManage;
        this.orgManage = orgManage;
        this.variableManage = variableManage;
    }

    // ------------------------------------------------------------------ 数据集与权限

    public CoreDatasetGroup requireDataset(Long datasetId) {
        CoreDatasetGroup dataset = datasetId == null ? null : datasetGroupRepository.findById(datasetId).orElse(null);
        if (dataset == null || StringUtils.equalsIgnoreCase(dataset.getNodeType(), "folder")) {
            DEException.throwException(Translator.get("ee_dataperm_dataset_not_exist"));
        }
        return dataset;
    }

    public String datasetName(Long datasetId) {
        return datasetId == null ? null : datasetGroupRepository.findById(datasetId).map(CoreDatasetGroup::getName).orElse(null);
    }

    /**
     * 只有可以管理数据集的用户（系统管理员、组织管理员、对数据集有管理权限者）可以配置其行列权限
     */
    public void checkManage(Long datasetId) {
        requireDataset(datasetId);
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException(Translator.get("ee_dataperm_no_manage"));
        }
        if (permissionManage.isSysAdmin(uid)) {
            return;
        }
        if (!permissionManage.current(EeConstants.RT_DATASET, datasetId).satisfies(EeConstants.WEIGHT_MANAGE)) {
            DEException.throwException(Translator.get("ee_dataperm_no_manage"));
        }
    }

    /**
     * 数据集所属组织；未登记时退回当前组织
     */
    public Long datasetOrg(Long datasetId) {
        Long oid = datasetId == null ? null : busiResourceManage.orgOf(datasetId);
        return oid != null ? oid : V3UserUtil.getOid();
    }

    /**
     * 当前请求是否为指定接口的 HTTP 调用（区分前端调用与核心模块的 Java 调用）
     */
    public static boolean isHttpCall(String pathFragment) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return false;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return StringUtils.contains(request.getRequestURI(), pathFragment);
    }

    // ------------------------------------------------------------------ 授权对象

    public static String normalizeTargetType(String type, boolean allowSysParams) {
        if (StringUtils.equalsIgnoreCase(type, DatapermConstants.TARGET_USER)) {
            return DatapermConstants.TARGET_USER;
        }
        if (StringUtils.equalsIgnoreCase(type, DatapermConstants.TARGET_ROLE)) {
            return DatapermConstants.TARGET_ROLE;
        }
        if (allowSysParams && StringUtils.equalsIgnoreCase(type, DatapermConstants.TARGET_SYS_PARAMS)) {
            return DatapermConstants.TARGET_SYS_PARAMS;
        }
        DEException.throwException(Translator.get("ee_dataperm_target_type_invalid"));
        return null;
    }

    /**
     * 授权对象必须存在且属于数据集所在组织
     */
    public void checkTarget(Long datasetId, String type, Long targetId) {
        if (targetId == null) {
            DEException.throwException(Translator.get("ee_dataperm_target_required"));
        }
        Long oid = datasetOrg(datasetId);
        boolean valid;
        if (DatapermConstants.TARGET_USER.equals(type)) {
            PerUser user = perUserRepository.findById(targetId).orElse(null);
            valid = user != null && !EeConstants.ADMIN_UID.equals(user.getId()) && (oid == null || oid.equals(user.getDefaultOid()));
        } else {
            PerRole role = perRoleRepository.findById(targetId).orElse(null);
            valid = role != null && (oid == null || oid.equals(role.getOid()));
        }
        if (!valid) {
            DEException.throwException(Translator.get("ee_dataperm_target_invalid"));
        }
    }

    public Map<Long, String> userNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (CollectionUtils.isNotEmpty(ids)) {
            perUserRepository.findAllById(new HashSet<>(ids)).forEach(user -> result.put(user.getId(), user.getName()));
        }
        return result;
    }

    public Map<Long, String> roleNames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (CollectionUtils.isNotEmpty(ids)) {
            perRoleRepository.findAllById(new HashSet<>(ids)).forEach(role -> result.put(role.getId(), role.getName()));
        }
        return result;
    }

    public List<UserFormVO> userOptions(Collection<Long> ids) {
        List<UserFormVO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(ids)) {
            return result;
        }
        Map<Long, PerUser> users = new HashMap<>();
        perUserRepository.findAllById(new LinkedHashSet<>(ids)).forEach(user -> users.put(user.getId(), user));
        for (Long id : new LinkedHashSet<>(ids)) {
            PerUser user = users.get(id);
            if (user == null) {
                continue;
            }
            UserFormVO vo = new UserFormVO();
            vo.setId(user.getId());
            vo.setName(user.getName());
            vo.setAccount(user.getAccount());
            vo.setEmail(user.getEmail());
            vo.setEnable(user.getEnable());
            vo.setDefaultOid(user.getDefaultOid());
            result.add(vo);
        }
        return result;
    }

    public List<RoleVO> roleOptions(Collection<Long> ids) {
        List<RoleVO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(ids)) {
            return result;
        }
        Map<Long, PerRole> roles = new HashMap<>();
        perRoleRepository.findAllById(new LinkedHashSet<>(ids)).forEach(role -> roles.put(role.getId(), role));
        for (Long id : new LinkedHashSet<>(ids)) {
            PerRole role = roles.get(id);
            if (role == null) {
                continue;
            }
            RoleVO vo = new RoleVO();
            vo.setId(role.getId());
            vo.setName(role.getName());
            vo.setRoot(Boolean.TRUE.equals(role.getRoot()));
            vo.setReadonly(Boolean.TRUE.equals(role.getReadonly()));
            vo.setTypeCode(role.getTypeCode());
            result.add(vo);
        }
        return result;
    }

    /**
     * 角色成员（白名单候选）
     */
    public List<UserFormVO> roleMembers(Long rid) {
        if (rid == null) {
            return new ArrayList<>();
        }
        return userOptions(roleManage.userIdsOfRole(rid));
    }

    /**
     * 数据集所在组织的用户或角色
     */
    public List<Item> orgSubjects(Long oid, String type) {
        List<Item> result = new ArrayList<>();
        if (oid == null) {
            return result;
        }
        if (DatapermConstants.TARGET_ROLE.equalsIgnoreCase(type)) {
            for (PerRole role : roleManage.rolesOfOrg(oid)) {
                Item item = new Item();
                item.setId(role.getId());
                item.setName(role.getName());
                result.add(item);
            }
        } else if (DatapermConstants.TARGET_USER.equalsIgnoreCase(type)) {
            for (PerUser user : orgUsers(oid)) {
                Item item = new Item();
                item.setId(user.getId());
                item.setName(user.getName());
                item.setAccount(user.getAccount());
                result.add(item);
            }
        }
        return result;
    }

    private List<PerUser> orgUsers(Long oid) {
        List<Long> ids = orgManage.memberIds(oid).stream().filter(id -> !EeConstants.ADMIN_UID.equals(id)).toList();
        List<PerUser> users = new ArrayList<>(perUserRepository.findAllById(ids));
        users.sort(Comparator.comparing((PerUser user) -> user.getCreateTime() == null ? 0L : user.getCreateTime()).reversed());
        return users;
    }

    /**
     * 规则编辑器的授权对象树：数据集所在组织节点（type 2）下挂用户（type 0）或角色（type 1）
     */
    public List<SubjectNode> subjectTree(Long oid, int type) {
        List<SubjectNode> roots = new ArrayList<>();
        PerOrg org = oid == null ? null : orgManage.get(oid);
        if (org == null) {
            return roots;
        }
        SubjectNode orgNode = new SubjectNode(org.getId(), 0L, org.getName(), 2);
        orgNode.setAttrs(new HashMap<>());
        orgNode.setChildren(new ArrayList<>());
        if (type == EeConstants.SUBJECT_ROLE) {
            for (PerRole role : roleManage.rolesOfOrg(oid)) {
                SubjectNode node = new SubjectNode(role.getId(), oid, role.getName(), 1);
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("root", Boolean.TRUE.equals(role.getRoot()));
                attrs.put("typeCode", role.getTypeCode());
                node.setAttrs(attrs);
                orgNode.getChildren().add(node);
            }
        } else {
            for (PerUser user : orgUsers(oid)) {
                SubjectNode node = new SubjectNode(user.getId(), oid, user.getName(), 0);
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("account", user.getAccount());
                node.setAttrs(attrs);
                orgNode.getChildren().add(node);
            }
        }
        roots.add(orgNode);
        return roots;
    }

    /**
     * 核心模块使用的用户信息：账号、姓名、邮箱、手机号、组织名、组织内角色及系统变量取值
     */
    public UserFormVO userForm(Long uid) {
        UserFormVO vo = new UserFormVO();
        vo.setRoleIds(new ArrayList<>());
        vo.setVariables(new ArrayList<>());
        PerUser user = uid == null ? null : perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            vo.setId(uid);
            return vo;
        }
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setEmail(user.getEmail());
        vo.setPhonePrefix(user.getPhonePrefix());
        vo.setPhone(user.getPhone());
        vo.setEnable(user.getEnable());
        vo.setOrigin(user.getOrigin());
        vo.setMfaEnable(Boolean.TRUE.equals(user.getMfaEnable()));
        vo.setDefaultOid(user.getDefaultOid());
        PerOrg org = user.getDefaultOid() == null ? null : orgManage.get(user.getDefaultOid());
        vo.setOrgName(org == null ? null : org.getName());
        if (user.getDefaultOid() != null) {
            vo.setRoleIds(new ArrayList<>(roleManage.roleIdsOfUser(user.getId(), user.getDefaultOid()).stream().map(String::valueOf).toList()));
        }
        vo.setVariables(new ArrayList<>(variableManage.userVariables(user.getId())));
        return vo;
    }

    // ------------------------------------------------------------------ JSON

    /**
     * 将白名单规范化为由字符串 ID 组成的 JSON 数组，空值返回 "[]"
     */
    public static String normalizeIdList(String json) {
        if (StringUtils.isBlank(json)) {
            return DatapermConstants.EMPTY_JSON_ARRAY;
        }
        List<Object> raw;
        try {
            raw = MAPPER.readValue(json, OBJECT_LIST);
        } catch (JsonProcessingException e) {
            DEException.throwException(Translator.get("ee_dataperm_white_list_invalid"));
            return null;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object item : raw == null ? List.of() : raw) {
            String text = item == null ? null : item.toString().trim();
            if (StringUtils.isEmpty(text)) {
                continue;
            }
            if (!text.matches("^\\d+$")) {
                DEException.throwException(Translator.get("ee_dataperm_white_list_invalid"));
            }
            ids.add(text);
        }
        return toJson(new ArrayList<>(ids));
    }

    public static List<Long> parseIdList(String json) {
        List<Long> result = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            return result;
        }
        try {
            for (Object item : MAPPER.readValue(json, OBJECT_LIST)) {
                if (item != null && item.toString().trim().matches("^\\d+$")) {
                    result.add(Long.valueOf(item.toString().trim()));
                }
            }
        } catch (JsonProcessingException | NumberFormatException e) {
            return result;
        }
        return result;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            DEException.throwException(e);
            return null;
        }
    }

    public static String defaultIds(String json) {
        return StringUtils.isBlank(json) ? DatapermConstants.EMPTY_JSON_ARRAY : json;
    }
}
