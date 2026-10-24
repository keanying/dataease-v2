package io.dataease.ee.log;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.api.permissions.login.dto.PwdLoginDTO;
import io.dataease.auth.vo.TokenVO;
import io.dataease.constant.LogOT;
import io.dataease.constant.LogST;
import io.dataease.ee.auth.LoginServer;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.LogUtil;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 登录、登出日志（资源类型“用户”）。通过切点拦截 {@link LoginServer}，不修改登录模块代码：
 *
 * <ul>
 *     <li>账号密码登录 / 第三方登录 / MFA 登录：成功且签发了 token 时记录（需要 MFA 的第一步不记录，
 *     MFA 完成时记录）；失败时记录失败原因，操作人为尝试登录的账号；</li>
 *     <li>登出：记录当前用户。</li>
 * </ul>
 */
@Aspect
@Component
@Order(5)
public class EeLoginLogAspect {

    private final EeLogManage logManage;

    private final JPAQueryFactory queryFactory;

    public EeLoginLogAspect(EeLogManage logManage, JPAQueryFactory queryFactory) {
        this.logManage = logManage;
        this.queryFactory = queryFactory;
    }

    @Around("execution(* io.dataease.ee.auth.LoginServer.localLogin(..)) || execution(* io.dataease.ee.auth.LoginServer.mfaLogin(..)) "
            + "|| execution(* io.dataease.ee.auth.LoginServer.platformLogin(..))")
    public Object login(ProceedingJoinPoint point) throws Throwable {
        EeLogManage.LogContext context = logManage.captureContext();
        Object result;
        try {
            result = point.proceed();
        } catch (Throwable e) {
            try {
                String account = attemptedAccount(point.getArgs());
                if (StringUtils.isNotBlank(account)) {
                    fillUser(context, account);
                    String detail = context.uid == null ? context.userName : context.userName + "(" + account + ")";
                    logManage.record(context, LogST.USER.getValue(), LogOT.LOGIN.getValue(), context.uid,
                            detail, false, e.getMessage());
                }
            } catch (Exception ex) {
                LogUtil.warn("record login failure log failed: " + ex.getMessage());
            }
            throw e;
        }
        try {
            if (result instanceof TokenVO tokenVO && StringUtils.isNotBlank(tokenVO.getToken())) {
                DecodedJWT decoded = JWT.decode(tokenVO.getToken());
                Long uid = decoded.getClaim("uid").asLong();
                Long oid = decoded.getClaim("oid").asLong();
                V3BaseUser user = V3UserUtil.getUser(uid);
                context.uid = uid;
                context.oid = oid != null ? oid : (user == null ? null : user.getDefaultOid());
                context.userName = user == null ? null : user.getName();
                String detail = user == null ? String.valueOf(uid) : user.getName() + "(" + user.getAccount() + ")";
                logManage.record(context, LogST.USER.getValue(), LogOT.LOGIN.getValue(), uid, detail, true, null);
            }
        } catch (Exception e) {
            LogUtil.warn("record login log failed: " + e.getMessage());
        }
        return result;
    }

    @Around("execution(* io.dataease.ee.auth.LoginServer.logout(..))")
    public Object logout(ProceedingJoinPoint point) throws Throwable {
        EeLogManage.LogContext context = logManage.captureContext();
        Object result = point.proceed();
        try {
            if (context.uid != null) {
                V3BaseUser user = V3UserUtil.getUser(context.uid);
                String detail = user == null ? context.userName : user.getName() + "(" + user.getAccount() + ")";
                logManage.record(context, LogST.USER.getValue(), EeLogTypes.OT_LOGOUT, context.uid, detail, true, null);
            }
        } catch (Exception e) {
            LogUtil.warn("record logout log failed: " + e.getMessage());
        }
        return result;
    }

    private String attemptedAccount(Object[] args) {
        if (args.length > 0 && args[0] instanceof PwdLoginDTO dto) {
            try {
                return LoginServer.decrypt(dto.getName());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void fillUser(EeLogManage.LogContext context, String account) {
        QPerUser q = QPerUser.perUser;
        Tuple t = queryFactory.select(q.id, q.name, q.defaultOid).from(q)
                .where(q.account.eq(account).or(q.email.eq(account))).fetchFirst();
        if (t != null) {
            context.uid = t.get(q.id);
            context.userName = t.get(q.name);
            context.oid = t.get(q.defaultOid);
        } else {
            context.userName = StringUtils.abbreviate(account, 64);
        }
    }
}
