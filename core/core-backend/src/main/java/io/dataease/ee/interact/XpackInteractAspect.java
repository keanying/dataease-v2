package io.dataease.ee.interact;

import io.dataease.license.config.XpackInteract;
import io.dataease.license.utils.LicenseUtil;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把开源核心中 {@code @XpackInteract} 标记的方法委托给企业版扩展 Bean。
 *
 * <p>扩展方法与被拦截方法同名，参数约定如下：</p>
 * <ul>
 *     <li>{@code replace = true}：参数与原方法一致，返回值替换原方法结果，原方法不执行；
 *     扩展方法也可以像 recursion 一样以 {@link EeInvocation} 为首个参数，按需调用原方法；</li>
 *     <li>{@code recursion = true}：首个参数为 {@link EeInvocation}，其余与原方法一致，返回值作为结果；</li>
 *     <li>{@code before = false} 或 {@code original = true}：先执行原方法，再调用扩展；扩展参数可在原方法参数后
 *     追加一个接收原方法返回值的参数；扩展返回非空且类型兼容时替换结果；</li>
 *     <li>默认（before）：先调用扩展（可修改入参对象），再执行原方法。</li>
 * </ul>
 * <p>找不到扩展 Bean 或扩展方法时原方法照常执行。</p>
 */
@Aspect
@Component
@Order(10)
public class XpackInteractAspect {

    private final ApplicationContext applicationContext;

    private volatile Map<String, Object> extensions;

    private final Map<String, Optional<Method>> methodCache = new ConcurrentHashMap<>();

    public XpackInteractAspect(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Around("@annotation(xpackInteract)")
    public Object around(ProceedingJoinPoint point, XpackInteract xpackInteract) throws Throwable {
        if (!LicenseUtil.licenseValid() && !xpackInteract.invalid()) {
            return point.proceed();
        }
        Object extension = extensions().get(xpackInteract.value());
        if (extension == null) {
            return point.proceed();
        }
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        Object[] args = point.getArgs();
        Class<?> returnType = method.getReturnType();

        if (xpackInteract.replace() || xpackInteract.recursion()) {
            Method target = find(extension, method, Mode.RECURSION);
            if (target != null) {
                EeInvocation invocation = () -> point.proceed(args);
                return invoke(target, extension, ArrayUtils.insert(0, args, invocation));
            }
        }
        if (xpackInteract.replace()) {
            Method target = find(extension, method, Mode.REPLACE);
            return target == null ? point.proceed() : invoke(target, extension, args);
        }
        if (xpackInteract.recursion()) {
            Method target = find(extension, method, Mode.RECURSION);
            if (target == null) {
                return point.proceed();
            }
            EeInvocation invocation = () -> point.proceed(args);
            return invoke(target, extension, ArrayUtils.insert(0, args, invocation));
        }
        if (!xpackInteract.before() || xpackInteract.original()) {
            Object result = point.proceed();
            Method target = find(extension, method, Mode.AFTER_WITH_RESULT);
            Object extResult;
            if (target != null) {
                extResult = invoke(target, extension, ArrayUtils.add(args, result));
            } else {
                target = find(extension, method, Mode.REPLACE);
                if (target == null) {
                    return result;
                }
                extResult = invoke(target, extension, args);
            }
            if (extResult != null && returnType != void.class && ClassUtils.isAssignableValue(returnType, extResult)) {
                return extResult;
            }
            return result;
        }
        Method target = find(extension, method, Mode.REPLACE);
        if (target != null) {
            invoke(target, extension, args);
        }
        return point.proceed();
    }

    private Object invoke(Method target, Object extension, Object[] args) throws Throwable {
        try {
            return target.invoke(extension, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private Map<String, Object> extensions() {
        Map<String, Object> local = extensions;
        if (local == null) {
            synchronized (this) {
                local = extensions;
                if (local == null) {
                    local = new HashMap<>();
                    for (Object bean : applicationContext.getBeansWithAnnotation(EeExtension.class).values()) {
                        EeExtension annotation = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(bean), EeExtension.class);
                        if (annotation == null) {
                            continue;
                        }
                        for (String name : annotation.value()) {
                            local.put(name, bean);
                        }
                    }
                    extensions = local;
                }
            }
        }
        return local;
    }

    private Method find(Object extension, Method origin, Mode mode) {
        Class<?> extClass = AopUtils.getTargetClass(extension);
        String key = extClass.getName() + "#" + origin.getDeclaringClass().getName() + "#" + origin.getName()
                + Arrays.toString(origin.getParameterTypes()) + "#" + mode;
        return methodCache.computeIfAbsent(key, k -> Optional.ofNullable(resolve(extClass, origin, mode))).orElse(null);
    }

    private Method resolve(Class<?> extClass, Method origin, Mode mode) {
        Class<?>[] originTypes = origin.getParameterTypes();
        for (Method candidate : extClass.getMethods()) {
            if (!candidate.getName().equals(origin.getName())) {
                continue;
            }
            Class<?>[] types = candidate.getParameterTypes();
            switch (mode) {
                case REPLACE -> {
                    if (assignable(types, 0, originTypes)) {
                        return candidate;
                    }
                }
                case RECURSION -> {
                    if (types.length == originTypes.length + 1 && types[0] == EeInvocation.class
                            && assignable(types, 1, originTypes)) {
                        return candidate;
                    }
                }
                case AFTER_WITH_RESULT -> {
                    if (origin.getReturnType() != void.class && types.length == originTypes.length + 1
                            && assignable(Arrays.copyOf(types, originTypes.length), 0, originTypes)
                            && ClassUtils.isAssignable(types[originTypes.length], origin.getReturnType())) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean assignable(Class<?>[] candidate, int offset, Class<?>[] origin) {
        if (candidate.length - offset != origin.length) {
            return false;
        }
        for (int i = 0; i < origin.length; i++) {
            if (!ClassUtils.isAssignable(candidate[i + offset], origin[i])) {
                return false;
            }
        }
        return true;
    }

    private enum Mode {
        REPLACE, RECURSION, AFTER_WITH_RESULT
    }
}
