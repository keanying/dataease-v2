package io.dataease.ee.log;

import io.dataease.constant.LogOT;
import io.dataease.constant.LogST;
import io.dataease.log.DeLog;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录 {@link DeLog} 标注方法的操作日志。
 *
 * <ul>
 *     <li>{@code id}/{@code pid}/{@code stExp} 为 SpEL，可使用 {@code #p0}、{@code #a0} 或参数名；</li>
 *     <li>执行前先解析一次资源名称（删除后无法再查到），执行后再解析一次（新建/重命名后的名称）；</li>
 *     <li>新建时请求中没有 ID，则取返回值（数字/字符串，或带 {@code getId()} 的对象）中的 ID；</li>
 *     <li>失败的操作也会记录（success=false，msg 为异常信息），异常原样抛出；</li>
 *     <li>切面在事务切面之外执行，日志以独立事务写入，业务回滚不影响失败日志。</li>
 * </ul>
 */
@Aspect
@Component
@Order(5)
public class EeLogAspect {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

    private final EeLogManage logManage;

    private final EeLogResourceManage resourceManage;

    public EeLogAspect(EeLogManage logManage, EeLogResourceManage resourceManage) {
        this.logManage = logManage;
        this.resourceManage = resourceManage;
    }

    @Around("@annotation(deLog)")
    public Object around(ProceedingJoinPoint point, DeLog deLog) throws Throwable {
        Prepared prepared = null;
        try {
            prepared = prepare(point, deLog);
        } catch (Exception e) {
            LogUtil.warn("prepare operation log failed: " + e.getMessage());
        }
        Object result;
        try {
            result = point.proceed();
        } catch (Throwable e) {
            if (prepared != null) {
                finish(point, deLog, prepared, null, e);
            }
            throw e;
        }
        if (prepared != null) {
            finish(point, deLog, prepared, result, null);
        }
        return result;
    }

    private Prepared prepare(ProceedingJoinPoint point, DeLog deLog) {
        Prepared prepared = new Prepared();
        prepared.context = logManage.captureContext();
        MethodBasedEvaluationContext ctx = evaluationContext(point);
        LogST st = null;
        if (StringUtils.isNotBlank(deLog.stExp())) {
            st = EeLogTypes.parseSource(eval(deLog.stExp(), ctx));
        }
        prepared.st = st == null ? deLog.st() : st;
        prepared.id = StringUtils.isBlank(deLog.id()) ? null : eval(deLog.id(), ctx);
        prepared.pid = StringUtils.isBlank(deLog.pid()) ? null : eval(deLog.pid(), ctx);
        if (isPresent(prepared.id)) {
            prepared.detail = resourceManage.resolve(prepared.st, prepared.id);
        }
        return prepared;
    }

    private void finish(ProceedingJoinPoint point, DeLog deLog, Prepared prepared, Object result, Throwable error) {
        try {
            Object id = prepared.id;
            String detail = prepared.detail;
            boolean deleted = deLog.ot() == LogOT.DELETE;
            if (error == null && !deleted) {
                if (!isPresent(id)) {
                    // 新建：请求对象在执行过程中可能已被赋予 ID，或从返回值取
                    if (StringUtils.isNotBlank(deLog.id())) {
                        id = eval(deLog.id(), evaluationContext(point));
                    }
                    if (!isPresent(id)) {
                        id = idOf(result);
                    }
                }
                if (isPresent(id)) {
                    String after = resourceManage.resolve(prepared.st, id);
                    if (StringUtils.isNotBlank(after)) {
                        detail = after;
                    }
                }
            }
            if (StringUtils.isBlank(detail)) {
                detail = nameOf(point.getArgs().length > 0 ? point.getArgs()[0] : null);
                if (StringUtils.isNotBlank(detail) && isPresent(prepared.pid)) {
                    String parent = resourceManage.resolve(prepared.st, prepared.pid);
                    if (StringUtils.isNotBlank(parent)) {
                        detail = parent + "/" + detail;
                    }
                }
            }
            if (StringUtils.isBlank(detail) && isPresent(id)) {
                detail = String.valueOf(id);
            }
            String msg = null;
            if (error != null) {
                msg = StringUtils.defaultIfBlank(error.getMessage(), ExceptionUtils.getRootCauseMessage(error));
            }
            logManage.record(prepared.context, prepared.st.getValue(), deLog.ot().getValue(), id, detail, error == null, msg);
        } catch (Exception e) {
            LogUtil.warn("record operation log failed: " + e.getMessage());
        }
    }

    private MethodBasedEvaluationContext evaluationContext(ProceedingJoinPoint point) {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        return new MethodBasedEvaluationContext(point.getArgs(), method, point.getArgs(), discoverer);
    }

    private Object eval(String expression, MethodBasedEvaluationContext ctx) {
        try {
            return expressions.computeIfAbsent(expression, parser::parseExpression).getValue(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPresent(Object id) {
        return id != null && StringUtils.isNotBlank(id.toString()) && !"0".equals(id.toString());
    }

    private static Object idOf(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof Number || result instanceof CharSequence) {
            return result;
        }
        return invokeGetter(result, "getId");
    }

    private static String nameOf(Object arg) {
        Object name = invokeGetter(arg, "getName");
        return name == null ? null : name.toString();
    }

    private static Object invokeGetter(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(getter);
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static class Prepared {
        EeLogManage.LogContext context;
        LogST st;
        Object id;
        Object pid;
        String detail;
    }
}
