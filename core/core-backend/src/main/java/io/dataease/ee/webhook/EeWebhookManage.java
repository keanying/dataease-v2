package io.dataease.ee.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.webhook.vo.WebhookGridVO;
import io.dataease.api.webhook.vo.WebhookOption;
import io.dataease.api.webhook.vo.WebhookVO;
import io.dataease.ee.webhook.dao.QXpackWebhook;
import io.dataease.ee.webhook.dao.XpackWebhook;
import io.dataease.ee.webhook.dao.XpackWebhookRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Webhook 配置管理（当前组织）
 */
@Component
public class EeWebhookManage {

    private static final Pattern URL_PATTERN = Pattern.compile("^(http|https)://([\\w.]+/?)\\S*$");

    private static final Set<String> CONTENT_TYPES = Set.of(EeWebhookSender.JSON, EeWebhookSender.FORM);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JPAQueryFactory queryFactory;

    private final XpackWebhookRepository repository;

    public EeWebhookManage(JPAQueryFactory queryFactory, XpackWebhookRepository repository) {
        this.queryFactory = queryFactory;
        this.repository = repository;
    }

    private Long currentOid() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return oid;
    }

    public PageResult<WebhookGridVO> pager(int goPage, int pageSize, String keyword) {
        int page = Math.max(goPage, 1);
        int size = Math.min(Math.max(pageSize, 1), 1000);
        QXpackWebhook q = QXpackWebhook.xpackWebhook;
        BooleanBuilder where = new BooleanBuilder(q.oid.eq(currentOid()));
        if (StringUtils.isNotBlank(keyword)) {
            String k = keyword.trim();
            where.and(q.name.containsIgnoreCase(k).or(q.url.containsIgnoreCase(k)));
        }
        Long total = queryFactory.select(q.id.count()).from(q).where(where).fetchOne();
        List<XpackWebhook> list = total == null || total == 0 ? List.of() : queryFactory.selectFrom(q).where(where)
                .orderBy(q.createTime.desc(), q.id.desc()).offset((long) (page - 1) * size).limit(size).fetch();
        List<WebhookGridVO> records = list.stream().map(item -> {
            WebhookGridVO vo = new WebhookGridVO();
            vo.setId(item.getId());
            vo.setName(item.getName());
            vo.setUrl(item.getUrl());
            vo.setSecret(item.getSecret());
            vo.setContentType(item.getContentType());
            vo.setSsl(!Boolean.FALSE.equals(item.getSsl()));
            return vo;
        }).toList();
        return new PageResult<>(records, total == null ? 0L : total, PageRequest.of(page - 1, size));
    }

    /**
     * 当前组织内的 webhook，不存在或不属于当前组织时抛出业务异常
     */
    public XpackWebhook getInOrg(Long id) {
        XpackWebhook webhook = id == null ? null : repository.findById(id).orElse(null);
        if (webhook == null || !Objects.equals(webhook.getOid(), currentOid())) {
            DEException.throwException(Translator.get("ee_webhook_not_exist"));
        }
        return webhook;
    }

    public WebhookVO get(Long id) {
        XpackWebhook item = getInOrg(id);
        WebhookVO vo = new WebhookVO();
        vo.setId(item.getId());
        vo.setName(item.getName());
        vo.setUrl(item.getUrl());
        vo.setSecret(item.getSecret());
        vo.setContentType(item.getContentType());
        vo.setSsl(!Boolean.FALSE.equals(item.getSsl()));
        vo.setMsgTemplate(item.getMsgTemplate());
        vo.setOid(item.getOid());
        vo.setCreateTime(item.getCreateTime());
        return vo;
    }

    @Transactional
    public Long save(WebhookVO vo) {
        if (vo == null) {
            DEException.throwException(Translator.get("ee_webhook_name_invalid"));
        }
        Long oid = currentOid();
        String name = StringUtils.trimToEmpty(vo.getName());
        if (name.isEmpty() || name.length() > 50) {
            DEException.throwException(Translator.get("ee_webhook_name_invalid"));
        }
        String url = StringUtils.trimToEmpty(vo.getUrl());
        if (url.length() < 10 || url.length() > 255 || !URL_PATTERN.matcher(url).matches()) {
            DEException.throwException(Translator.get("ee_webhook_url_invalid"));
        }
        String contentType = StringUtils.defaultIfBlank(StringUtils.trimToNull(vo.getContentType()), EeWebhookSender.JSON);
        if (!CONTENT_TYPES.contains(contentType)) {
            DEException.throwException(Translator.get("ee_webhook_content_type_invalid"));
        }
        String template = StringUtils.trimToNull(vo.getMsgTemplate());
        if (template != null && EeWebhookSender.JSON.equals(contentType) && !validJsonTemplate(template)) {
            DEException.throwException(Translator.get("ee_webhook_template_invalid"));
        }
        String secret = StringUtils.trimToNull(vo.getSecret());
        if (secret != null && secret.length() > 255) {
            DEException.throwException(Translator.get("ee_webhook_secret_invalid"));
        }
        QXpackWebhook q = QXpackWebhook.xpackWebhook;
        BooleanBuilder dup = new BooleanBuilder(q.oid.eq(oid)).and(q.name.eq(name));
        if (vo.getId() != null) {
            dup.and(q.id.ne(vo.getId()));
        }
        if (queryFactory.selectOne().from(q).where(dup).fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_webhook_name_exists"));
        }
        long now = System.currentTimeMillis();
        if (vo.getId() == null) {
            XpackWebhook entity = new XpackWebhook();
            entity.setId(IDUtils.snowID());
            entity.setName(name);
            entity.setUrl(url);
            entity.setContentType(contentType);
            entity.setSecret(secret);
            entity.setSsl(!Boolean.FALSE.equals(vo.getSsl()));
            entity.setMsgTemplate(template);
            entity.setOid(oid);
            entity.setCreateBy(V3UserUtil.getUid());
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            repository.save(entity);
            return entity.getId();
        }
        XpackWebhook entity = getInOrg(vo.getId());
        // 允许清空 secret 与模板：直接更新，避免 save() 的非空合并
        queryFactory.update(q)
                .set(q.name, name)
                .set(q.url, url)
                .set(q.contentType, contentType)
                .set(q.secret, secret)
                .set(q.ssl, !Boolean.FALSE.equals(vo.getSsl()))
                .set(q.msgTemplate, template)
                .set(q.updateTime, now)
                .where(q.id.eq(entity.getId()))
                .execute();
        return entity.getId();
    }

    private static boolean validJsonTemplate(String template) {
        try {
            MAPPER.readTree(template);
            return true;
        } catch (Exception e) {
            try {
                // 允许不带引号的占位符（如数字字段）
                MAPPER.readTree(template.replaceAll("\\$\\{[A-Za-z0-9_.\\-]+}", "0"));
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    @Transactional
    public void switchSsl(Long id, boolean ssl) {
        XpackWebhook entity = getInOrg(id);
        QXpackWebhook q = QXpackWebhook.xpackWebhook;
        queryFactory.update(q).set(q.ssl, ssl).set(q.updateTime, System.currentTimeMillis())
                .where(q.id.eq(entity.getId())).execute();
    }

    @Transactional
    public void delete(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        QXpackWebhook q = QXpackWebhook.xpackWebhook;
        queryFactory.delete(q).where(q.id.in(ids), q.oid.eq(currentOid())).execute();
    }

    public List<WebhookOption> options() {
        QXpackWebhook q = QXpackWebhook.xpackWebhook;
        return queryFactory.selectFrom(q).where(q.oid.eq(currentOid())).orderBy(q.createTime.desc()).fetch().stream()
                .map(item -> {
                    WebhookOption option = new WebhookOption();
                    option.setId(item.getId());
                    option.setName(item.getName());
                    return option;
                }).toList();
    }
}
