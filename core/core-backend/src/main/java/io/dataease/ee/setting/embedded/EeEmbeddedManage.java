package io.dataease.ee.setting.embedded;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.api.permissions.embedded.dto.EmbeddedCreator;
import io.dataease.api.permissions.embedded.dto.EmbeddedEditor;
import io.dataease.api.permissions.embedded.dto.EmbeddedResetRequest;
import io.dataease.api.permissions.embedded.vo.EmbeddedGridVO;
import io.dataease.auth.interceptor.CorsConfig;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.ee.setting.embedded.dao.PerEmbedded;
import io.dataease.ee.setting.embedded.dao.PerEmbeddedRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import io.dataease.utils.Md5Utils;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 嵌入式应用管理与嵌入式 token 校验。
 *
 * <p>嵌入式 token 由宿主系统生成：HS256 JWT，以应用的 appSecret 签名，声明 {@code appId}（应用）与 {@code account}
 * （以哪个 DataEase 用户身份访问，兼容 {@code user}），可选 {@code exp}；其余声明作为外部参数（/embedded/getTokenArgs）。</p>
 */
@Component
public class EeEmbeddedManage {

    /**
     * 不作为外部参数返回的保留声明
     */
    public static final Set<String> RESERVED_CLAIMS = Set.of("appId", "account", "user", "uid", "exp", "iat", "nbf", "iss", "sub", "aud", "jti");

    private static final Pattern DOMAIN_PATTERN = Pattern.compile("(http|https)://([\\w.]+/?)\\S*");

    private final PerEmbeddedRepository repository;

    private final PerUserRepository perUserRepository;

    private final ObjectProvider<CorsConfig> corsConfig;

    /**
     * 最多应用数，0 表示不限
     */
    @Value("${dataease.embedded.limit:0}")
    private int limit;

    /**
     * 已注销 token 的摘要 -> 过期时间
     */
    private final Map<String, Long> destroyed = new ConcurrentHashMap<>();

    public EeEmbeddedManage(PerEmbeddedRepository repository, PerUserRepository perUserRepository, ObjectProvider<CorsConfig> corsConfig) {
        this.repository = repository;
        this.perUserRepository = perUserRepository;
        this.corsConfig = corsConfig;
    }

    public int limit() {
        return Math.max(limit, 0);
    }

    public PageResult<EmbeddedGridVO> pager(int goPage, int pageSize, String keyword) {
        PageRequest pageable = PageRequest.of(Math.max(goPage, 1) - 1, Math.max(pageSize, 1), Sort.by(Sort.Direction.DESC, "createTime"));
        Specification<PerEmbedded> spec = (root, query, cb) -> {
            if (StringUtils.isBlank(keyword)) {
                return null;
            }
            String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            Predicate name = cb.like(cb.lower(root.get("name")), like);
            Predicate appId = cb.like(cb.lower(root.get("appId")), like);
            Predicate domain = cb.like(cb.lower(root.get("domain")), like);
            return cb.or(name, appId, domain);
        };
        Page<PerEmbedded> page = repository.findAll(spec, pageable);
        List<EmbeddedGridVO> records = page.getContent().stream().map(this::convert).toList();
        return new PageResult<>(records, page.getTotalElements(), pageable);
    }

    @Transactional
    public synchronized void create(EmbeddedCreator creator) {
        if (limit() > 0 && repository.count() >= limit()) {
            DEException.throwException(Translator.get("ee_settings.embedded_limit", limit()));
        }
        String name = checkName(creator.getName());
        String domain = checkDomain(creator.getDomain());
        int secretLength = checkSecretLength(creator.getSecretLength());
        PerEmbedded po = new PerEmbedded();
        po.setId(IDUtils.snowID());
        po.setName(name);
        po.setDomain(domain);
        po.setSecretLength(secretLength);
        String appId;
        do {
            appId = EeSettingSupport.randomAlphanumeric(16);
        } while (repository.findByAppId(appId).isPresent());
        po.setAppId(appId);
        po.setAppSecret(EeSettingSupport.randomAlphanumeric(secretLength));
        po.setCreateBy(V3UserUtil.getUid());
        po.setCreateTime(System.currentTimeMillis());
        po.setUpdateTime(po.getCreateTime());
        repository.save(po);
        syncCors();
    }

    @Transactional
    public void edit(EmbeddedEditor editor) {
        PerEmbedded po = get(editor.getId());
        po.setName(checkName(editor.getName()));
        po.setDomain(checkDomain(editor.getDomain()));
        int secretLength = checkSecretLength(editor.getSecretLength());
        if (!Objects.equals(po.getSecretLength(), secretLength)) {
            po.setSecretLength(secretLength);
            po.setAppSecret(EeSettingSupport.randomAlphanumeric(secretLength));
        }
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
        syncCors();
    }

    @Transactional
    public void reset(EmbeddedResetRequest request) {
        PerEmbedded po = get(request.getId());
        if (StringUtils.isNotBlank(request.getAppSecret()) && !StringUtils.equals(request.getAppSecret(), po.getAppSecret())) {
            DEException.throwException(Translator.get("ee_settings.embedded_secret_mismatch"));
        }
        int secretLength = po.getSecretLength() == null ? 16 : po.getSecretLength();
        String secret;
        do {
            secret = EeSettingSupport.randomAlphanumeric(secretLength);
        } while (StringUtils.equals(secret, po.getAppSecret()));
        po.setAppSecret(secret);
        po.setUpdateTime(System.currentTimeMillis());
        repository.save(po);
    }

    @Transactional
    public void delete(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        repository.deleteAllById(ids.stream().filter(Objects::nonNull).filter(repository::existsById).toList());
        syncCors();
    }

    public List<String> domainList() {
        Set<String> result = new LinkedHashSet<>();
        for (PerEmbedded po : repository.findAll()) {
            result.addAll(origins(po.getDomain()));
        }
        return new ArrayList<>(result);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncCors() {
        CorsConfig config = corsConfig.getIfAvailable();
        if (config == null) {
            return;
        }
        try {
            config.addAllowedOrigins(domainList());
        } catch (Exception e) {
            LogUtil.warn("sync embedded domains to cors failed: " + e.getMessage());
        }
    }

    private PerEmbedded get(Long id) {
        PerEmbedded po = id == null ? null : repository.findById(id).orElse(null);
        if (po == null) {
            DEException.throwException(Translator.get("ee_settings.embedded_not_exist"));
        }
        return po;
    }

    private String checkName(String name) {
        String value = StringUtils.trimToEmpty(name);
        if (value.isEmpty() || value.length() > 50) {
            DEException.throwException(Translator.get("ee_settings.embedded_name_invalid"));
        }
        return value;
    }

    private String checkDomain(String domain) {
        String value = StringUtils.trimToEmpty(domain);
        if (value.length() < 10 || value.length() > 100 || !DOMAIN_PATTERN.matcher(value).find() || origins(value).isEmpty()) {
            DEException.throwException(Translator.get("ee_settings.embedded_domain_invalid"));
        }
        return value;
    }

    private int checkSecretLength(Integer secretLength) {
        int value = secretLength == null ? 16 : secretLength;
        if (value < 4 || value > 32) {
            DEException.throwException(Translator.get("ee_settings.embedded_secret_length_invalid"));
        }
        return value;
    }

    private EmbeddedGridVO convert(PerEmbedded po) {
        EmbeddedGridVO vo = new EmbeddedGridVO();
        vo.setId(po.getId());
        vo.setName(po.getName());
        vo.setAppId(po.getAppId());
        vo.setAppSecret(po.getAppSecret());
        vo.setDomain(po.getDomain());
        vo.setSecretLength(po.getSecretLength());
        return vo;
    }

    // ------------------------------------------------------------------ token

    /**
     * 校验嵌入式 token，失败抛出 {@link DEException}（code 为未登录）
     */
    public Verified verify(String token) {
        if (StringUtils.isBlank(token) || StringUtils.equals(token, "null")) {
            fail("embedded token is empty");
        }
        DecodedJWT decoded = null;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            fail("embedded token is invalid");
        }
        String appId = decoded.getClaim("appId").asString();
        if (StringUtils.isBlank(appId)) {
            fail("embedded token is invalid");
        }
        PerEmbedded app = repository.findByAppId(appId).orElse(null);
        if (app == null) {
            fail("embedded app not exist");
        }
        try {
            JWT.require(Algorithm.HMAC256(app.getAppSecret())).build().verify(decoded);
        } catch (TokenExpiredException e) {
            fail("token is Expired");
        } catch (JWTVerificationException e) {
            fail("embedded token is invalid");
        }
        if (destroyed.containsKey(Md5Utils.md5(token))) {
            fail("token is destroyed");
        }
        String account = decoded.getClaim("account").asString();
        if (StringUtils.isBlank(account)) {
            account = decoded.getClaim("user").asString();
        }
        if (StringUtils.isBlank(account)) {
            fail("embedded token is invalid: account is empty");
        }
        String finalAccount = account.trim();
        PerUser user = perUserRepository.findOne((root, query, cb) -> cb.equal(root.get("account"), finalAccount)).orElse(null);
        if (user == null) {
            fail("user_disable: user not exist");
        }
        if (!Boolean.TRUE.equals(user.getEnable())) {
            fail("user_disable");
        }
        return new Verified(app, user.getId(), claims(decoded));
    }

    private Map<String, Object> claims(DecodedJWT decoded) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(decoded.getPayload()), StandardCharsets.UTF_8);
            Map<String, Object> map = JsonUtil.parseObject(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * token 中的外部参数（去掉保留声明）
     */
    public Map<String, Object> outerParams(Map<String, Object> claims) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (claims != null) {
            claims.forEach((k, v) -> {
                if (!RESERVED_CLAIMS.contains(k)) {
                    result.put(k, v);
                }
            });
        }
        return result;
    }

    public void destroy(String token) {
        if (StringUtils.isBlank(token)) {
            return;
        }
        long now = System.currentTimeMillis();
        destroyed.entrySet().removeIf(entry -> entry.getValue() < now);
        long exp = now + 24L * 3600 * 1000;
        try {
            Date expiresAt = JWT.decode(token).getExpiresAt();
            if (expiresAt != null) {
                exp = expiresAt.getTime();
            }
        } catch (JWTVerificationException ignored) {
            // 无法解析的 token 按默认时间记录
        }
        destroyed.put(Md5Utils.md5(token), exp);
    }

    /**
     * 来源是否在应用允许的域名内（比较 scheme://host[:port]，忽略默认端口与末尾斜杠）
     */
    public boolean originAllowed(PerEmbedded app, String origin) {
        String normalized = normalizeOrigin(origin);
        return normalized != null && origins(app.getDomain()).contains(normalized);
    }

    public static List<String> origins(String domain) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isBlank(domain)) {
            return result;
        }
        for (String item : domain.split("[,;，；\\s]+")) {
            String origin = normalizeOrigin(item);
            if (origin != null && !result.contains(origin)) {
                result.add(origin);
            }
        }
        return result;
    }

    public static String normalizeOrigin(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            URI uri = URI.create(text.trim());
            String scheme = StringUtils.lowerCase(uri.getScheme());
            String host = StringUtils.lowerCase(uri.getHost());
            if (!StringUtils.equalsAny(scheme, "http", "https") || StringUtils.isBlank(host)) {
                return null;
            }
            int port = uri.getPort();
            if (port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                return scheme + "://" + host;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    private void fail(String message) {
        DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), message);
    }

    /**
     * 校验通过的嵌入式 token
     *
     * @param app    应用
     * @param uid    访问用户
     * @param claims 全部声明
     */
    public record Verified(PerEmbedded app, Long uid, Map<String, Object> claims) {
    }
}
