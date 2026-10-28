package io.dataease.ee.log;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.log.dto.LogGridRequest;
import io.dataease.api.log.vo.LogGridVO;
import io.dataease.api.log.vo.LogOpVO;
import io.dataease.constant.LogOT;
import io.dataease.constant.LogST;
import io.dataease.ee.log.dao.QXpackLog;
import io.dataease.ee.log.dao.XpackLog;
import io.dataease.ee.log.dao.XpackLogRepository;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.IPUtils;
import io.dataease.utils.LogUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 操作日志：记录、查询、导出与过期清理。
 *
 * <p>可见范围：</p>
 * <ul>
 *     <li>系统管理员（非代理）—— 全部日志；</li>
 *     <li>系统管理员代理组织、组织管理员 —— {@code OrgApi.logScope()} 返回的组织（当前组织及子组织）内的日志；</li>
 *     <li>其他用户 —— 仅本人的日志。</li>
 * </ul>
 */
@Component
public class EeLogManage {

    private static final int MAX_EXPORT_ROWS = 1000000;

    private static final int BATCH = 2000;

    private final JPAQueryFactory queryFactory;

    private final XpackLogRepository repository;

    private final EePermissionManage permissionManage;

    private final EeSettingManage settingManage;

    private final TransactionTemplate requiresNew;

    public EeLogManage(JPAQueryFactory queryFactory, XpackLogRepository repository, EePermissionManage permissionManage,
                       EeSettingManage settingManage, PlatformTransactionManager transactionManager) {
        this.queryFactory = queryFactory;
        this.repository = repository;
        this.permissionManage = permissionManage;
        this.settingManage = settingManage;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /* ------------------------------------------------------------------ 记录 */

    /**
     * 当前请求/线程的操作人、组织、IP、客户端快照
     */
    public LogContext captureContext() {
        LogContext context = new LogContext();
        V3BaseUser user = V3UserUtil.getUser();
        if (user != null) {
            context.uid = user.getId();
            context.oid = user.getOid();
            context.userName = user.getName();
        }
        try {
            context.ip = IPUtils.get();
        } catch (Exception ignored) {
            // 无请求上下文
        }
        HttpServletRequest request = ServletUtils.request();
        context.client = request != null && StringUtils.equalsIgnoreCase(request.getHeader("X-DE-MOBILE"), "true")
                ? EeLogTypes.CLIENT_MOBILE : EeLogTypes.CLIENT_PC;
        return context;
    }

    /**
     * 以独立事务写入一条日志；任何异常都只记录到系统日志，不影响业务
     */
    public void record(LogContext context, int sourceType, int operateType, Object resourceId, String detail,
                       boolean success, String msg) {
        try {
            XpackLog log = new XpackLog();
            log.setId(IDUtils.snowID());
            log.setUid(context.uid);
            log.setOid(context.oid);
            log.setUserName(StringUtils.abbreviate(context.userName, 255));
            log.setSourceType(sourceType);
            log.setOperateType(operateType);
            log.setResourceId(resourceId == null ? null : StringUtils.abbreviate(resourceId.toString(), 64));
            log.setOpDetail(StringUtils.abbreviate(detail, 1024));
            log.setIp(StringUtils.abbreviate(context.ip, 64));
            log.setClient(context.client);
            log.setSuccess(success);
            log.setMsg(StringUtils.abbreviate(msg, 2000));
            log.setCreateTime(System.currentTimeMillis());
            requiresNew.executeWithoutResult(status -> repository.save(log));
        } catch (Exception e) {
            LogUtil.error("save operation log failed: " + e.getMessage(), e);
        }
    }

    /**
     * 供其他模块直接记录日志（当前线程的身份与请求）
     */
    public void record(LogST st, LogOT ot, Object resourceId, String detail) {
        record(captureContext(), st.getValue(), ot.getValue(), resourceId, detail, true, null);
    }

    /* ------------------------------------------------------------------ 范围 */

    /**
     * 日志可见范围
     *
     * @param orgScope {@code OrgApi.logScope()} 的结果：null 表示全部
     */
    public LogScope scope(List<Long> orgScope) {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        boolean proxy = V3UserUtil.getProxy().isProxy();
        boolean sysAdmin = V3UserUtil.isSysAdmin(uid);
        if (sysAdmin && !proxy && orgScope == null) {
            return LogScope.all();
        }
        Long oid = V3UserUtil.getOid();
        if (sysAdmin || (oid != null && permissionManage.isOrgAdmin(uid, oid))) {
            List<Long> oids = orgScope == null ? (oid == null ? List.of() : List.of(oid)) : orgScope;
            return LogScope.orgs(oids);
        }
        return LogScope.self(uid);
    }

    /* ------------------------------------------------------------------ 查询 */

    public PageResult<LogGridVO> pager(int goPage, int pageSize, LogGridRequest request, LogScope scope) {
        int page = Math.max(goPage, 1);
        int size = Math.min(Math.max(pageSize, 1), 1000);
        QXpackLog q = QXpackLog.xpackLog;
        BooleanBuilder where = predicate(request, scope);
        Long total = queryFactory.select(q.id.count()).from(q).where(where).fetchOne();
        List<XpackLog> list = total == null || total == 0 ? List.of() : query(request, where)
                .offset((long) (page - 1) * size).limit(size).fetch();
        return new PageResult<>(list.stream().map(this::convert).toList(), total == null ? 0L : total,
                PageRequest.of(page - 1, size));
    }

    private JPAQuery<XpackLog> query(LogGridRequest request, BooleanBuilder where) {
        QXpackLog q = QXpackLog.xpackLog;
        boolean desc = request == null || !Boolean.FALSE.equals(request.getTimeDesc());
        return queryFactory.selectFrom(q).where(where)
                .orderBy(desc ? q.createTime.desc() : q.createTime.asc(), desc ? q.id.desc() : q.id.asc());
    }

    private LogGridVO convert(XpackLog log) {
        LogGridVO vo = new LogGridVO();
        vo.setOpText(EeLogTypes.opText(log.getSourceType(), log.getOperateType()));
        vo.setOpDetail(log.getOpDetail());
        vo.setName(log.getUserName());
        vo.setIp(log.getIp());
        vo.setTime(log.getCreateTime());
        vo.setSuccess(!Boolean.FALSE.equals(log.getSuccess()));
        vo.setMsg(log.getMsg());
        return vo;
    }

    private BooleanBuilder predicate(LogGridRequest request, LogScope scope) {
        QXpackLog q = QXpackLog.xpackLog;
        BooleanBuilder where = new BooleanBuilder();
        if (scope == null) {
            // 防御：未提供范围时不返回任何数据
            where.and(q.id.isNull());
            return where;
        }
        if (scope.selfUid() != null) {
            where.and(q.uid.eq(scope.selfUid()));
        } else if (scope.oids() != null) {
            where.and(scope.oids().isEmpty() ? q.id.isNull() : q.oid.in(scope.oids()));
        }
        if (request == null) {
            return where;
        }
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            where.and(q.userName.containsIgnoreCase(keyword)
                    .or(q.opDetail.containsIgnoreCase(keyword))
                    .or(q.ip.containsIgnoreCase(keyword)));
        }
        if (CollectionUtils.isNotEmpty(request.getOp())) {
            BooleanBuilder ops = new BooleanBuilder();
            Set<Integer> wholeSources = new HashSet<>();
            for (String item : request.getOp()) {
                if (StringUtils.isBlank(item)) {
                    continue;
                }
                String[] parts = item.trim().split("-");
                Integer st = toInt(parts[0]);
                if (st == null) {
                    continue;
                }
                if (parts.length == 1) {
                    wholeSources.add(st);
                } else {
                    Integer ot = toInt(parts[1]);
                    if (ot != null) {
                        ops.or(q.sourceType.eq(st).and(q.operateType.eq(ot)));
                    }
                }
            }
            if (!wholeSources.isEmpty()) {
                ops.or(q.sourceType.in(wholeSources));
            }
            if (ops.hasValue()) {
                where.and(ops);
            }
        }
        if (CollectionUtils.isNotEmpty(request.getClient())) {
            List<String> clients = request.getClient().stream().filter(StringUtils::isNotBlank).map(String::toLowerCase).toList();
            if (!clients.isEmpty()) {
                BooleanExpression expr = q.client.in(clients);
                if (clients.contains(EeLogTypes.CLIENT_PC)) {
                    expr = expr.or(q.client.isNull());
                }
                where.and(expr);
            }
        }
        List<Long> time = request.getTime();
        if (CollectionUtils.isNotEmpty(time)) {
            if (time.get(0) != null) {
                where.and(q.createTime.goe(time.get(0)));
            }
            if (time.size() > 1 && time.get(1) != null) {
                where.and(q.createTime.loe(time.get(1)));
            }
        }
        if (CollectionUtils.isNotEmpty(request.getOid())) {
            where.and(q.oid.in(request.getOid()));
        }
        if (CollectionUtils.isNotEmpty(request.getUid())) {
            where.and(q.uid.in(request.getUid()));
        }
        return where;
    }

    private static Integer toInt(String text) {
        String value = StringUtils.trimToEmpty(text);
        return StringUtils.isNumeric(value) && value.length() < 10 ? Integer.parseInt(value) : null;
    }

    /**
     * 操作类型筛选树：一级为资源类型（value=st），二级为操作类型（value=st-ot）
     */
    public List<LogOpVO> options() {
        Map<Integer, List<Integer>> sourceOps = EeLogTypes.sourceOps();
        QXpackLog q = QXpackLog.xpackLog;
        try {
            List<Tuple> used = queryFactory.select(q.sourceType, q.operateType).distinct().from(q)
                    .where(q.sourceType.isNotNull(), q.operateType.isNotNull()).fetch();
            for (Tuple tuple : used) {
                List<Integer> ops = sourceOps.computeIfAbsent(tuple.get(q.sourceType), k -> new ArrayList<>());
                if (!ops.contains(tuple.get(q.operateType))) {
                    ops.add(tuple.get(q.operateType));
                }
            }
        } catch (Exception e) {
            LogUtil.warn("load used log types failed: " + e.getMessage());
        }
        List<LogOpVO> result = new ArrayList<>();
        sourceOps.forEach((st, ops) -> {
            List<LogOpVO> children = ops.stream()
                    .map(ot -> new LogOpVO(st + "-" + ot, EeLogTypes.operateLabel(ot), null)).toList();
            result.add(new LogOpVO(String.valueOf(st), EeLogTypes.sourceLabel(st), new ArrayList<>(children)));
        });
        return result;
    }

    /* ------------------------------------------------------------------ 导出 */

    /**
     * 写出 Excel（导出中心异步线程调用，线程上已设置身份、代理组织与语言）
     */
    public void writeExcel(String filePath, LogGridRequest request, LogScope scope) throws Exception {
        File file = new File(filePath);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        QXpackLog q = QXpackLog.xpackLog;
        BooleanBuilder where = predicate(request, scope);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500); OutputStream out = new FileOutputStream(file)) {
            Sheet sheet = wb.createSheet(Translator.get("ee_log_export_name"));
            CellStyle headStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headStyle.setFont(font);
            String[] heads = {
                    Translator.get("ee_log_excel_op"), Translator.get("ee_log_excel_detail"),
                    Translator.get("ee_log_excel_status"), Translator.get("ee_log_excel_user"),
                    Translator.get("ee_log_excel_ip"), Translator.get("ee_log_excel_time"),
                    Translator.get("ee_log_excel_msg")
            };
            Row head = sheet.createRow(0);
            for (int i = 0; i < heads.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(heads[i]);
                cell.setCellStyle(headStyle);
                sheet.setColumnWidth(i, (i == 1 || i == 6 ? 50 : 20) * 256);
            }
            int rowIndex = 1;
            long offset = 0;
            String success = Translator.get("ee_log_success");
            String failed = Translator.get("ee_log_failed");
            while (rowIndex <= MAX_EXPORT_ROWS) {
                List<XpackLog> batch = query(request, where).offset(offset).limit(BATCH).fetch();
                if (batch.isEmpty()) {
                    break;
                }
                for (XpackLog log : batch) {
                    if (rowIndex > MAX_EXPORT_ROWS) {
                        break;
                    }
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(EeLogTypes.opText(log.getSourceType(), log.getOperateType()));
                    row.createCell(1).setCellValue(StringUtils.defaultString(log.getOpDetail()));
                    row.createCell(2).setCellValue(Boolean.FALSE.equals(log.getSuccess()) ? failed : success);
                    row.createCell(3).setCellValue(StringUtils.defaultString(log.getUserName()));
                    row.createCell(4).setCellValue(StringUtils.defaultString(log.getIp()));
                    row.createCell(5).setCellValue(log.getCreateTime() == null ? "" : format.format(new Date(log.getCreateTime())));
                    row.createCell(6).setCellValue(StringUtils.defaultString(log.getMsg()));
                }
                offset += batch.size();
                if (batch.size() < BATCH) {
                    break;
                }
            }
            wb.write(out);
            wb.dispose();
        }
    }

    /* ------------------------------------------------------------------ 清理 */

    /**
     * 每天凌晨按“操作日志保留时间”（basic.logLiveTime，天，默认 30）删除过期日志；
     * 执行时间可用 {@code dataease.ee.log.clean-cron} 调整
     */
    @Scheduled(cron = "${dataease.ee.log.clean-cron:0 23 2 * * ?}")
    public void cleanExpired() {
        try {
            int days = settingManage.getInt("basic.logLiveTime", 30);
            if (days <= 0) {
                return;
            }
            long threshold = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
            Long count = clean(threshold);
            LogUtil.info("clean expired operation logs: " + count);
        } catch (Exception e) {
            LogUtil.error("clean expired operation logs failed: " + e.getMessage(), e);
        }
    }

    public Long clean(long beforeTime) {
        QXpackLog q = QXpackLog.xpackLog;
        return requiresNew.execute(status -> queryFactory.delete(q).where(q.createTime.lt(beforeTime)).execute());
    }

    /* ------------------------------------------------------------------ 类型 */

    public static class LogContext {
        public Long uid;
        public Long oid;
        public String userName;
        public String ip;
        public String client;
    }

    /**
     * 日志可见范围：selfUid 非空时仅本人；oids 非空时为组织范围；两者都为空表示全部
     */
    public record LogScope(List<Long> oids, Long selfUid) {

        public static LogScope all() {
            return new LogScope(null, null);
        }

        public static LogScope orgs(List<Long> oids) {
            return new LogScope(oids == null ? List.of() : oids, null);
        }

        public static LogScope self(Long uid) {
            return new LogScope(null, uid);
        }
    }
}
