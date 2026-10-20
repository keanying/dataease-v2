package io.dataease.permission.util;

import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.model.V3ProxyInfo;
import io.dataease.permission.spi.V3UserProvider;

/**
 * 当前请求（或当前异步任务线程）的访问身份。
 *
 * <p>Web 请求由 token 过滤器设置并在请求结束后清理；异步任务需要按既有模式显式
 * {@link #setUid(Long)}/{@link #setProxy(Long)}，并在 {@code finally} 中调用 {@link #clear()}。</p>
 */
public class V3UserUtil {

    public static final Long SYS_ADMIN_UID = 1L;

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private static volatile V3UserProvider PROVIDER;

    public static void registerProvider(V3UserProvider provider) {
        PROVIDER = provider;
    }

    /**
     * 设置当前身份
     *
     * @param uid 用户 ID
     * @param oid 当前组织 ID，可为空（为空时取用户默认组织）
     */
    public static void setContext(Long uid, Long oid) {
        if (uid == null) {
            CONTEXT.remove();
            return;
        }
        Context context = new Context();
        context.uid = uid;
        context.oid = oid;
        CONTEXT.set(context);
    }

    public static void setUid(Long uid) {
        setContext(uid, null);
    }

    public static Long getUid() {
        Context context = CONTEXT.get();
        return context == null ? null : context.uid;
    }

    /**
     * 本次请求生效的组织 ID（代理时为被代理组织）
     */
    public static Long getOid() {
        V3BaseUser user = getUser();
        return user == null ? null : user.getOid();
    }

    public static void setProxy(Long proxyOid) {
        Context context = CONTEXT.get();
        if (context == null) {
            return;
        }
        context.proxyOid = proxyOid;
        context.cachedUser = null;
    }

    public static V3ProxyInfo getProxy() {
        Context context = CONTEXT.get();
        return new V3ProxyInfo(context == null ? null : context.proxyOid);
    }

    /**
     * 当前身份。未登录时返回 {@code null}。
     */
    public static V3BaseUser getUser() {
        Context context = CONTEXT.get();
        if (context == null || context.uid == null) {
            return null;
        }
        if (context.cachedUser != null) {
            return context.cachedUser;
        }
        V3BaseUser stored = getUser(context.uid);
        V3BaseUser user = new V3BaseUser();
        if (stored != null) {
            copy(stored, user);
        }
        user.setId(context.uid);
        Long oid = context.proxyOid != null ? context.proxyOid : context.oid;
        user.setOid(oid != null ? oid : user.getDefaultOid());
        context.cachedUser = user;
        return user;
    }

    public static V3BaseUser getUser(Long uid) {
        V3UserProvider provider = PROVIDER;
        if (uid == null || provider == null) {
            return null;
        }
        return provider.getUser(uid);
    }

    public static boolean isSysAdmin() {
        return isSysAdmin(getUid());
    }

    public static boolean isSysAdmin(Long uid) {
        if (uid == null) {
            return false;
        }
        V3UserProvider provider = PROVIDER;
        if (provider == null) {
            return SYS_ADMIN_UID.equals(uid);
        }
        return provider.isSysAdmin(uid);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private static void copy(V3BaseUser source, V3BaseUser target) {
        target.setAccount(source.getAccount());
        target.setName(source.getName());
        target.setEmail(source.getEmail());
        target.setPhone(source.getPhone());
        target.setDefaultOid(source.getDefaultOid());
        target.setPwd(source.getPwd());
        target.setEnable(source.getEnable());
        target.setOrigin(source.getOrigin());
        target.setLanguage(source.getLanguage());
    }

    private static final class Context {
        private Long uid;
        private Long oid;
        private Long proxyOid;
        private V3BaseUser cachedUser;
    }
}
