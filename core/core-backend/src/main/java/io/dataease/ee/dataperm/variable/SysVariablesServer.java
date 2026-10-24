package io.dataease.ee.dataperm.variable;

import io.dataease.api.permissions.variable.api.SysVariablesApi;
import io.dataease.api.permissions.variable.dto.SysVariableDto;
import io.dataease.api.permissions.variable.dto.SysVariableValueDto;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.dataset.EeDatapermSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.api.permissions.user.vo.UserFormVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 系统变量（/sysVariable）。
 *
 * <p>变量与变量值的维护仅限系统管理员；变量列表、详情与变量值列表对已登录用户开放，
 * 供用户表单（组织管理员）、行权限编辑与 SQL 数据集编辑选择变量。</p>
 */
@RestController("sysVariablesServer")
@RequestMapping("/sysVariable")
public class SysVariablesServer implements SysVariablesApi {

    private final EeSysVariableManage variableManage;

    private final EeDatapermSupport support;

    public SysVariablesServer(EeSysVariableManage variableManage, EeDatapermSupport support) {
        this.variableManage = variableManage;
        this.support = support;
    }

    private static void checkAdmin() {
        if (!V3UserUtil.isSysAdmin()) {
            DEException.throwException(Translator.get("ee_dataperm_no_permission"));
        }
    }

    private static void checkLogin() {
        if (V3UserUtil.getUid() == null) {
            DEException.throwException(Translator.get("ee_dataperm_no_permission"));
        }
    }

    @Override
    public SysVariableDto create(SysVariableDto sysVariableDto) {
        checkAdmin();
        return variableManage.create(sysVariableDto);
    }

    @Override
    public SysVariableDto edit(SysVariableDto sysVariableDto) {
        checkAdmin();
        return variableManage.edit(sysVariableDto);
    }

    @Override
    public void delete(Long id) {
        checkAdmin();
        variableManage.delete(id);
    }

    @Override
    public SysVariableDto detail(Long id) {
        checkLogin();
        return variableManage.detail(id);
    }

    @Override
    public List<SysVariableDto> query(SysVariableDto sysVariableDto) {
        checkLogin();
        return variableManage.list();
    }

    @Override
    public SysVariableValueDto createValue(SysVariableValueDto sysVariableDto) {
        checkAdmin();
        return variableManage.createValue(sysVariableDto);
    }

    @Override
    public SysVariableValueDto editValue(SysVariableValueDto sysVariableDto) {
        checkAdmin();
        if (sysVariableDto.getId() == null) {
            return variableManage.createValue(sysVariableDto);
        }
        return variableManage.editValue(sysVariableDto);
    }

    @Override
    public void deleteValue(String id) {
        checkAdmin();
        if (StringUtils.isBlank(id) || !id.trim().matches("^\\d+$")) {
            DEException.throwException(Translator.get("ee_dataperm_value_not_exist"));
        }
        variableManage.deleteValues(List.of(Long.valueOf(id.trim())));
    }

    @Override
    public List<SysVariableValueDto> selectVariableValue(Long id) {
        checkLogin();
        variableManage.require(id);
        return variableManage.valueDtos(id);
    }

    @Override
    public PageResult<SysVariableValueDto> selectPage(int goPage, int pageSize, SysVariableValueDto sysVariableValueDto) {
        checkAdmin();
        return variableManage.valuePage(goPage, pageSize, sysVariableValueDto.getSysVariableId(), sysVariableValueDto.getValue());
    }

    @Override
    public void batchDel(List<Long> ids) {
        checkAdmin();
        variableManage.deleteValues(ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList());
    }

    /**
     * uid -> (变量ID / sysParams.xxx -> 值)
     */
    @Override
    public Map<Long, Map<String, String>> queryBatchSysVariable(List<Long> uids) {
        Map<Long, Map<String, String>> builtins = new HashMap<>();
        if (uids == null) {
            return builtins;
        }
        for (Long uid : uids) {
            if (uid == null) {
                continue;
            }
            UserFormVO user = support.userForm(uid);
            Map<String, String> values = new LinkedHashMap<>();
            putIfPresent(values, DatapermConstants.SYS_USER_ID, user.getAccount());
            putIfPresent(values, DatapermConstants.SYS_USER_NAME, user.getName());
            putIfPresent(values, DatapermConstants.SYS_USER_EMAIL, user.getEmail());
            putIfPresent(values, DatapermConstants.SYS_USER_PHONE, user.getPhone());
            putIfPresent(values, DatapermConstants.SYS_ORG_NAME, user.getOrgName());
            builtins.put(uid, values);
        }
        return variableManage.batchValues(uids.stream().filter(Objects::nonNull).toList(), builtins);
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (StringUtils.isNotEmpty(value)) {
            values.put(key, value);
        }
    }
}
