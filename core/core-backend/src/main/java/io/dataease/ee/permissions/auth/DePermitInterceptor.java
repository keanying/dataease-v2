package io.dataease.ee.permissions.auth;

import io.dataease.auth.DeApiPath;
import io.dataease.auth.DePermit;
import io.dataease.constant.AuthResourceEnum;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.exception.DEException;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DePermit} 鉴权。
 *
 * <p>表达式求值结果形如 {@code 目标:动作}：</p>
 * <ul>
 *     <li>{@code m:read}：菜单权限，菜单由 busiFlag（如 {@code SYSUSER|USER}，任一满足即可）或接口上的 {@link DeApiPath#rt()} 决定；</li>
 *     <li>{@code <资源ID>:read|use|export|export_view|export_detail|manage|auth}：资源权限，资源类型由 busiFlag
 *     （如 {@code dashboard}/{@code dataV}/{@code dataset}）或 {@link DeApiPath#rt()} 决定；</li>
 *     <li>用户、角色、组织等管理对象按“是否可管理该对象”判断。</li>
 * </ul>
 * <p>多个表达式需同时满足。</p>
 */
public class DePermitInterceptor implements MethodInterceptor {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private static final DefaultParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    private static final Map<String, Expression> EXPRESSIONS = new ConcurrentHashMap<>();

    private final EePermissionManage permissionManage;

    private final EeEntityPermit entityPermit;

    private final EeMenuManage menuManage;

    public DePermitInterceptor(EePermissionManage permissionManage, EeEntityPermit entityPermit, EeMenuManage menuManage) {
        this.permissionManage = permissionManage;
        this.entityPermit = entityPermit;
        this.menuManage = menuManage;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = target == null ? method.getDeclaringClass() : AopUtils.getTargetClass(target);
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        DePermit permit = AnnotationUtils.findAnnotation(specific, DePermit.class);
        if (permit == null || ArrayUtils.isEmpty(permit.value())) {
            return invocation.proceed();
        }
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            deny();
        }
        if (permissionManage.isSysAdmin(uid)) {
            return invocation.proceed();
        }
        DeApiPath apiPath = AnnotationUtils.findAnnotation(targetClass, DeApiPath.class);
        AuthResourceEnum rt = apiPath == null ? null : apiPath.rt();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(target, specific, invocation.getArguments(), DISCOVERER);
        String busiFlag = StringUtils.isBlank(permit.busiFlag()) ? null : evaluate(permit.busiFlag(), context);
        for (String expression : permit.value()) {
            String result = evaluate(expression, context);
            if (!check(result, busiFlag, rt)) {
                deny();
            }
        }
        return invocation.proceed();
    }

    private boolean check(String result, String busiFlag, AuthResourceEnum rt) {
        if (StringUtils.isBlank(result) || !result.contains(":")) {
            return false;
        }
        String targetText = StringUtils.substringBeforeLast(result, ":").trim();
        String action = StringUtils.substringAfterLast(result, ":").trim().toLowerCase();
        if (StringUtils.equals(targetText, "m")) {
            return checkMenu(busiFlag, rt);
        }
        Long resourceId;
        try {
            resourceId = Long.valueOf(targetText);
        } catch (NumberFormatException e) {
            return false;
        }
        int level = level(action);
        Integer flag = resourceFlag(busiFlag, rt);
        if (flag == null) {
            return checkMenu(busiFlag, rt);
        }
        return switch (flag) {
            case EeConstants.RT_PANEL, EeConstants.RT_SCREEN, EeConstants.RT_DATASET, EeConstants.RT_DATASOURCE,
                 EeConstants.RT_DATA_FILLING, EeConstants.RT_SPREADSHEET ->
                    permissionManage.effective(V3UserUtil.getUid(), V3UserUtil.getOid(), flag, resourceId <= 0 ? 0L : resourceId).satisfies(level);
            case 5 -> entityPermit.canManageUser(resourceId) || level <= EeConstants.WEIGHT_READ && checkMenu(busiFlag, rt);
            case 6 -> entityPermit.canManageRole(resourceId);
            case 7 -> entityPermit.canManageOrg(resourceId);
            default -> checkMenu(busiFlag, rt);
        };
    }

    private boolean checkMenu(String busiFlag, AuthResourceEnum rt) {
        Set<Long> visible = menuManage.currentVisibleMenuIds();
        List<Long> menuIds = new ArrayList<>();
        if (StringUtils.isNotBlank(busiFlag)) {
            for (String flag : busiFlag.split("\\|")) {
                AuthResourceEnum item = parseEnum(flag);
                if (item != null) {
                    menuIds.add(item.getMenuId());
                }
            }
        }
        if (menuIds.isEmpty() && rt != null) {
            menuIds.add(rt.getMenuId());
        }
        if (menuIds.isEmpty()) {
            return false;
        }
        return menuIds.stream().anyMatch(visible::contains);
    }

    private Integer resourceFlag(String busiFlag, AuthResourceEnum rt) {
        if (StringUtils.isNotBlank(busiFlag) && !busiFlag.contains("|")) {
            switch (busiFlag.trim().toLowerCase()) {
                case "dashboard", "panel":
                    return EeConstants.RT_PANEL;
                case "datav", "screen":
                    return EeConstants.RT_SCREEN;
                case "dataset":
                    return EeConstants.RT_DATASET;
                case "datasource":
                    return EeConstants.RT_DATASOURCE;
                case "data_filling", "datafilling", "data-filling":
                    return EeConstants.RT_DATA_FILLING;
                case "spreadsheet":
                    return EeConstants.RT_SPREADSHEET;
                default:
                    AuthResourceEnum item = parseEnum(busiFlag);
                    if (item != null) {
                        return item.getFlag();
                    }
            }
        }
        return rt == null ? null : rt.getFlag();
    }

    private AuthResourceEnum parseEnum(String flag) {
        try {
            return AuthResourceEnum.valueOf(flag.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int level(String action) {
        return switch (action) {
            case "use" -> EeConstants.WEIGHT_USE;
            case "export" -> 4;
            case "export_view" -> 5;
            case "export_detail" -> 6;
            case "manage" -> EeConstants.WEIGHT_MANAGE;
            case "auth" -> EeConstants.WEIGHT_AUTH;
            default -> EeConstants.WEIGHT_READ;
        };
    }

    private String evaluate(String expression, MethodBasedEvaluationContext context) {
        if (!expression.contains("#")) {
            return expression;
        }
        Expression parsed = EXPRESSIONS.computeIfAbsent(expression, PARSER::parseExpression);
        Object value = parsed.getValue(context);
        return value == null ? null : value.toString();
    }

    private void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }
}
