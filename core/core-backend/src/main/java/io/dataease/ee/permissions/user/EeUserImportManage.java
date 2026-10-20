package io.dataease.ee.permissions.user;

import com.alibaba.excel.EasyExcel;
import io.dataease.api.permissions.user.dto.UserCreator;
import io.dataease.api.permissions.user.vo.UserImportVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.QPerOrg;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.ServletUtils;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户批量导入。模板列：账号*、姓名*、邮箱、手机号、组织（系统用户导入时有效）、角色（多个以逗号分隔）、错误信息（仅错误报告）。
 */
@Component
public class EeUserImportManage {

    private static final List<String> HEADERS = List.of("账号*", "姓名*", "邮箱", "手机号", "组织", "角色");

    private final EeUserManage userManage;

    private final EeRoleManage roleManage;

    private final JPAQueryFactory queryFactory;

    private final Map<String, byte[]> errorReports = new ConcurrentHashMap<>();

    public EeUserImportManage(EeUserManage userManage, EeRoleManage roleManage, JPAQueryFactory queryFactory) {
        this.userManage = userManage;
        this.roleManage = roleManage;
        this.queryFactory = queryFactory;
    }

    public void template() {
        write(List.of(HEADERS), "user.xlsx");
    }

    /**
     * @param scopeOid 组织管理员导入时的当前组织；系统管理员导入时为 null（按“组织”列分配）
     */
    public UserImportVO importUsers(MultipartFile file, Long scopeOid) {
        List<List<String>> rows = read(file);
        if (rows.isEmpty() || !StringUtils.startsWith(rows.get(0).isEmpty() ? null : rows.get(0).get(0), "账号")) {
            DEException.throwException(Translator.get("ee_import_template_error"));
        }
        List<List<String>> errors = new ArrayList<>();
        int success = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(StringUtils::isBlank)) {
                continue;
            }
            try {
                importRow(row, scopeOid);
                success++;
            } catch (Exception e) {
                List<String> errorRow = new ArrayList<>(pad(row));
                errorRow.add(e.getMessage());
                errors.add(errorRow);
            }
        }
        UserImportVO vo = new UserImportVO();
        vo.setSuccessCount(success);
        vo.setErrorCount(errors.size());
        if (!errors.isEmpty()) {
            List<List<String>> report = new ArrayList<>();
            List<String> header = new ArrayList<>(HEADERS);
            header.add("错误信息");
            report.add(header);
            report.addAll(errors);
            String key = UUID.randomUUID().toString();
            errorReports.put(key, toBytes(report));
            vo.setDataKey(key);
        }
        return vo;
    }

    public void errorRecord(String key) {
        byte[] bytes = errorReports.get(key);
        if (bytes == null) {
            DEException.throwException(Translator.get("ee_import_template_error"));
        }
        download(bytes, "error.xlsx");
    }

    public void clear(String key) {
        if (key != null) {
            errorReports.remove(key);
        }
    }

    private void importRow(List<String> row, Long scopeOid) {
        List<String> cells = pad(row);
        String account = StringUtils.trim(cells.get(0));
        String name = StringUtils.trim(cells.get(1));
        if (StringUtils.isAnyBlank(account, name)) {
            throw new IllegalArgumentException("账号、姓名不能为空");
        }
        if (!account.matches("^[a-zA-Z0-9][a-zA-Z0-9@._-]*$") || account.length() > 50) {
            throw new IllegalArgumentException(Translator.get("ee_account_exists"));
        }
        Long oid = scopeOid;
        if (oid == null && StringUtils.isNotBlank(cells.get(4))) {
            QPerOrg q = QPerOrg.perOrg;
            PerOrg org = queryFactory.selectFrom(q).where(q.name.eq(cells.get(4).trim())).fetchFirst();
            if (org == null) {
                throw new IllegalArgumentException(Translator.get("ee_org_not_exist"));
            }
            oid = org.getId();
        }
        List<Long> roleIds = new ArrayList<>();
        if (oid != null) {
            List<PerRole> roles = roleManage.rolesOfOrg(oid);
            if (StringUtils.isNotBlank(cells.get(5))) {
                for (String roleName : cells.get(5).split("[,，]")) {
                    if (StringUtils.isBlank(roleName)) {
                        continue;
                    }
                    PerRole role = roles.stream().filter(item -> item.getName().equals(roleName.trim())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(Translator.get("ee_role_not_in_org")));
                    roleIds.add(role.getId());
                }
            } else {
                PerRole staff = roleManage.builtinRole(oid, EeConstants.ROLE_TYPE_STAFF);
                if (staff != null) {
                    roleIds.add(staff.getId());
                }
            }
        }
        UserCreator creator = new UserCreator();
        creator.setAccount(account);
        creator.setName(name);
        creator.setEmail(StringUtils.trimToNull(cells.get(2)));
        creator.setPhone(StringUtils.trimToNull(cells.get(3)));
        creator.setEnable(true);
        creator.setDefaultOid(oid);
        creator.setRoleIds(roleIds);
        try {
            userManage.create(creator, EeConstants.ORIGIN_LOCAL, null);
        } catch (DEException e) {
            throw new IllegalArgumentException(e.getMsg());
        }
    }

    private List<String> pad(List<String> row) {
        List<String> cells = new ArrayList<>(row);
        while (cells.size() < HEADERS.size()) {
            cells.add(null);
        }
        return cells.subList(0, HEADERS.size());
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            DEException.throwException(Translator.get("ee_import_template_error"));
        }
        try (InputStream in = file.getInputStream()) {
            List<Object> raw = EasyExcel.read(in).headRowNumber(0).sheet().doReadSync();
            List<List<String>> rows = new ArrayList<>();
            for (Object item : raw) {
                Map<Integer, Object> map = (Map<Integer, Object>) item;
                int max = map.keySet().stream().max(Integer::compareTo).orElse(-1);
                List<String> row = new ArrayList<>();
                for (int i = 0; i <= max; i++) {
                    Object value = map.get(i);
                    row.add(value == null ? null : value.toString());
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            DEException.throwException(e);
            return Collections.emptyList();
        }
    }

    private byte[] toBytes(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = rows.get(0).stream().map(List::of).toList();
        EasyExcel.write(out).head(head).sheet("sheet1").doWrite(rows.subList(1, rows.size()));
        return out.toByteArray();
    }

    private void write(List<List<String>> rows, String fileName) {
        download(toBytes(rows), fileName);
    }

    private void download(byte[] bytes, String fileName) {
        HttpServletResponse response = ServletUtils.response();
        if (response == null) {
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        try {
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        } catch (IOException e) {
            DEException.throwException(e);
        }
    }
}
