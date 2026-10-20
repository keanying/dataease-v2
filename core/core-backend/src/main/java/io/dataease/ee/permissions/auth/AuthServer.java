package io.dataease.ee.permissions.auth;

import io.dataease.api.permissions.auth.api.AuthApi;
import io.dataease.api.permissions.auth.dto.*;
import io.dataease.api.permissions.auth.vo.PermissionVO;
import io.dataease.api.permissions.auth.vo.ResourceItemVO;
import io.dataease.api.permissions.auth.vo.ResourceVO;
import io.dataease.api.permissions.auth.vo.SubjectNode;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限配置接口。仅系统管理员与可访问“权限配置”菜单的组织管理员可用。
 */
@RestController("authServer")
@RequestMapping("/auth")
public class AuthServer implements AuthApi {

    private static final Long ORG_AUTH_MENU = 10L;

    private final EeAuthService authService;

    private final EeMenuManage menuManage;

    public AuthServer(EeAuthService authService, EeMenuManage menuManage) {
        this.authService = authService;
        this.menuManage = menuManage;
    }

    private void check(boolean system) {
        if (V3UserUtil.isSysAdmin()) {
            return;
        }
        if (system || !menuManage.canAccess(ORG_AUTH_MENU)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    @Override
    public List<ResourceVO> busiResource(BusiResourceRequest request) {
        check(request.isSystem());
        return authService.busiResource(request);
    }

    @Override
    public PermissionVO subjectPermission(BasePermissionRequest request) {
        check(request.isSystem());
        return authService.subjectPermission(request);
    }

    @Override
    public PermissionVO resourcePermission(BasePermissionRequest request) {
        check(request.isSystem());
        return authService.resourcePermission(request);
    }

    @Override
    public void saveSubjectPermission(SubjectPermissionEditor editor) {
        check(false);
        authService.saveSubjectPermission(editor);
    }

    @Override
    public void saveResourcePermission(ResourcePermissionEditor creator) {
        check(false);
        authService.saveResourcePermission(creator);
    }

    @Override
    public List<ResourceItemVO> busiTargetPermissionAll(BasePermissionRequest request) {
        return new ArrayList<>();
    }

    @Override
    public List<SubjectNode> subjectTree(SubjectRequest request) {
        check(request.isSystem());
        return authService.subjectTree(request);
    }
}
