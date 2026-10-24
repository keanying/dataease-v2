package io.dataease.ee.sso.ldap;

import io.dataease.api.xpack.settings.vo.XpackLdapVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.query.SearchScope;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LDAP 认证：管理员 DN 绑定后在各 OU 下按过滤条件查找用户，再以用户 DN + 密码绑定校验。
 */
@Component
public class EeLdapManage {

    private static final Pattern FILTER_ATTR = Pattern.compile("\\(\\s*([\\w.-]+)\\s*=\\s*\\{0}\\s*\\)");

    private final EeAuthConfigManage configManage;

    public EeLdapManage(EeAuthConfigManage configManage) {
        this.configManage = configManage;
    }

    /**
     * 校验连接配置，成功返回 "true"，失败返回错误信息
     */
    public String validate(XpackLdapVO vo) {
        try {
            checkRequired(vo);
            Map<String, String> mapping = EeJson.mapping(vo.getMapping());
            LdapContextSource source = contextSource(vo);
            DirContext ctx = source.getReadOnlyContext();
            closeQuietly(ctx);
            LdapTemplate template = template(source);
            for (String ou : ous(vo)) {
                template.lookup(ou);
            }
            if (StringUtils.isBlank(accountAttribute(vo, mapping))) {
                return Translator.get("ee_ldap_account_mapping_missing");
            }
            return "true";
        } catch (IllegalArgumentException e) {
            return Translator.get("ee_sso_mapping_invalid") + ": " + e.getMessage();
        } catch (Exception e) {
            LogUtil.warn("ldap validate failed: " + e.getMessage());
            return rootMessage(e);
        }
    }

    /**
     * 账号密码认证，返回 LDAP 中的用户信息
     */
    public ExternalIdentity authenticate(String account, String pwd) {
        XpackLdapVO vo = configManage.requireActive(EeSsoConstants.LDAP, XpackLdapVO.class);
        if (StringUtils.isBlank(account) || StringUtils.isEmpty(pwd)) {
            // 空密码会触发 LDAP 匿名绑定，必须拒绝
            DEException.throwException(Translator.get("i18n_login_name_pwd_err"));
        }
        Map<String, String> mapping = EeJson.mapping(vo.getMapping());
        LdapContextSource source = contextSource(vo);
        LdapTemplate template = template(source);
        String filter = filter(vo, mapping, account.trim());
        List<DirContextAdapter> found = new ArrayList<>();
        try {
            for (String ou : ous(vo)) {
                found.addAll(template.search(LdapQueryBuilder.query().base(ou).searchScope(SearchScope.SUBTREE).countLimit(2)
                        .filter(filter), new AbstractContextMapper<DirContextAdapter>() {
                    @Override
                    protected DirContextAdapter doMapFromContext(org.springframework.ldap.core.DirContextOperations ctx) {
                        return (DirContextAdapter) ctx;
                    }
                }));
                if (found.size() > 1) {
                    break;
                }
            }
        } catch (Exception e) {
            LogUtil.error("ldap search failed: " + e.getMessage());
            DEException.throwException(Translator.get("ee_ldap_unavailable", rootMessage(e)));
        }
        if (found.isEmpty()) {
            DEException.throwException(Translator.get("i18n_login_name_pwd_err"));
        }
        if (found.size() > 1) {
            DEException.throwException(Translator.get("ee_ldap_user_ambiguous"));
        }
        DirContextAdapter entry = found.get(0);
        String dn = entry.getNameInNamespace();
        DirContext userCtx = null;
        try {
            userCtx = source.getContext(dn, pwd);
        } catch (Exception e) {
            DEException.throwException(Translator.get("i18n_login_name_pwd_err"));
        } finally {
            closeQuietly(userCtx);
        }
        Attributes attributes = entry.getAttributes();
        String accountAttr = accountAttribute(vo, mapping);
        String ldapAccount = StringUtils.defaultIfBlank(attr(attributes, accountAttr), account.trim());
        String name = attr(attributes, mapping.getOrDefault("name", "cn"));
        String email = attr(attributes, mapping.getOrDefault("email", "mail"));
        String phone = attr(attributes, mapping.getOrDefault("phone", "mobile"));
        return new ExternalIdentity(EeConstants.ORIGIN_LDAP, ldapAccount, ldapAccount, name, email, phone);
    }

    private void checkRequired(XpackLdapVO vo) {
        if (vo == null || StringUtils.isAnyBlank(vo.getAddr(), vo.getDn(), vo.getPwd(), vo.getOu(), vo.getFilter())) {
            throw new IllegalStateException(Translator.get("ee_sso_config_incomplete"));
        }
        String addr = vo.getAddr().trim().toLowerCase(Locale.ROOT);
        if (!addr.startsWith("ldap://") && !addr.startsWith("ldaps://")) {
            throw new IllegalStateException(Translator.get("ee_ldap_addr_invalid"));
        }
    }

    private LdapContextSource contextSource(XpackLdapVO vo) {
        checkRequired(vo);
        LdapContextSource source = new LdapContextSource();
        source.setUrls(Arrays.stream(StringUtils.split(vo.getAddr().trim(), " ,")).map(String::trim).toArray(String[]::new));
        source.setUserDn(vo.getDn().trim());
        source.setPassword(vo.getPwd());
        source.setPooled(false);
        source.setAnonymousReadOnly(false);
        Map<String, Object> env = new HashMap<>();
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "10000");
        env.put("java.naming.referral", "follow");
        source.setBaseEnvironmentProperties(env);
        source.afterPropertiesSet();
        return source;
    }

    private LdapTemplate template(LdapContextSource source) {
        LdapTemplate template = new LdapTemplate(source);
        template.setIgnorePartialResultException(true);
        return template;
    }

    private List<String> ous(XpackLdapVO vo) {
        List<String> list = new ArrayList<>();
        for (String ou : StringUtils.split(vo.getOu(), '|')) {
            if (StringUtils.isNotBlank(ou)) {
                list.add(ou.trim());
            }
        }
        if (list.isEmpty()) {
            throw new IllegalStateException(Translator.get("ee_sso_config_incomplete"));
        }
        return list;
    }

    /**
     * 构造用户过滤条件：支持 "(uid={0})"、"uid"、以及不含 {0} 的附加条件（与账号属性组合）
     */
    String filter(XpackLdapVO vo, Map<String, String> mapping, String account) {
        String template = vo.getFilter().trim();
        String encoded = LdapEncoder.filterEncode(account);
        if (template.contains("{0}")) {
            return template.replace("{0}", encoded);
        }
        if (!template.startsWith("(")) {
            return "(" + template + "=" + encoded + ")";
        }
        String accountAttr = StringUtils.defaultIfBlank(mapping.get("account"), "uid");
        return "(&" + template + "(" + accountAttr + "=" + encoded + "))";
    }

    private String accountAttribute(XpackLdapVO vo, Map<String, String> mapping) {
        if (StringUtils.isNotBlank(mapping.get("account"))) {
            return mapping.get("account");
        }
        String template = StringUtils.trimToEmpty(vo.getFilter());
        Matcher matcher = FILTER_ATTR.matcher(template);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (!template.startsWith("(") && StringUtils.isNotBlank(template)) {
            return template;
        }
        return "uid";
    }

    private String attr(Attributes attributes, String name) {
        if (attributes == null || StringUtils.isBlank(name)) {
            return null;
        }
        try {
            Attribute attribute = attributes.get(name);
            if (attribute == null || attribute.size() == 0) {
                return null;
            }
            NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore()) {
                Object value = values.next();
                if (value instanceof byte[] bytes) {
                    value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                if (value != null && StringUtils.isNotBlank(value.toString())) {
                    return value.toString().trim();
                }
            }
        } catch (Exception e) {
            LogUtil.debug("read ldap attribute " + name + " failed: " + e.getMessage());
        }
        return null;
    }

    private void closeQuietly(DirContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = StringUtils.defaultIfBlank(root.getMessage(), e.getMessage());
        return StringUtils.defaultIfBlank(message, e.getClass().getSimpleName());
    }
}
