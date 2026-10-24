package io.dataease.ee.threshold;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.threshold.dao.QXpackThresholdInfo;
import io.dataease.ee.threshold.dao.QXpackThresholdInstance;
import io.dataease.license.utils.LicenseUtil;
import io.dataease.utils.LogUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 按 basic.thresholdLogLiveTime（天，默认 30）清理阈值告警检测记录，并清理已删除告警的记录。
 */
@Component
public class ThresholdLogCleaner {

    private static final long DAY = 24L * 3600 * 1000;

    private final JPAQueryFactory queryFactory;

    private final EeSettingManage settingManage;

    private final TransactionTemplate transactionTemplate;

    public ThresholdLogCleaner(JPAQueryFactory queryFactory, EeSettingManage settingManage, TransactionTemplate transactionTemplate) {
        this.queryFactory = queryFactory;
        this.settingManage = settingManage;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(cron = "0 20 0 * * ?")
    public void clean() {
        if (!LicenseUtil.licenseValid()) {
            return;
        }
        try {
            long count = cleanNow();
            LogUtil.info("threshold logs cleaned: " + count);
        } catch (Exception e) {
            LogUtil.error("clean threshold logs failed: " + e.getMessage(), e);
        }
    }

    public long cleanNow() {
        int days = Math.max(1, Math.min(4000, settingManage.getInt("basic.thresholdLogLiveTime", 30)));
        long cutoff = System.currentTimeMillis() - days * DAY;
        QXpackThresholdInstance i = QXpackThresholdInstance.xpackThresholdInstance;
        QXpackThresholdInfo t = QXpackThresholdInfo.xpackThresholdInfo;
        Long result = transactionTemplate.execute(s -> {
            long expired = queryFactory.delete(i).where(i.execTime.lt(cutoff)).execute();
            long orphan = queryFactory.delete(i).where(i.thresholdId.notIn(JPAExpressions.select(t.id).from(t))).execute();
            return expired + orphan;
        });
        return result == null ? 0 : result;
    }
}
