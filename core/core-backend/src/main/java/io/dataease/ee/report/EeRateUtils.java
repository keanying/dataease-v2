package io.dataease.ee.report;

import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.quartz.CronExpression;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行频率 → Quartz cron。
 *
 * <p>前端频率编码：</p>
 * <ul>
 *     <li>报告 rateType=0：rateVal 为 Quartz cron；</li>
 *     <li>告警 rateType=0：每小时，rateValue 为分钟 "0".."59"；</li>
 *     <li>rateType=1/2/3（每天/每周/每月）：rateVal 为伪时间 {@code "2021-08-DD HH:mm:ss"}，
 *     每天 DD=01；每周 DD=0N，N 为 1(周一)..7(周日)；每月 DD 为日期 01..31；HH:mm:ss 为执行时间。</li>
 * </ul>
 */
public final class EeRateUtils {

    private static final Pattern FAKE_TIME = Pattern.compile("^\\s*\\d{4}-\\d{1,2}-(\\d{1,2})\\s+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?\\s*$");

    private static final String[] QUARTZ_WEEK = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private EeRateUtils() {
    }

    /**
     * 定时报告的 cron
     */
    public static String reportCron(Integer rateType, String rateVal) {
        if (rateType == null || rateType == 0) {
            return validCron(rateVal);
        }
        return fakeTimeCron(rateType, rateVal);
    }

    /**
     * 阈值告警的 cron
     */
    public static String thresholdCron(Integer rateType, String rateValue) {
        if (rateType == null || rateType == 0) {
            int minute = NumberUtils.toInt(StringUtils.trim(rateValue), -1);
            if (minute < 0 || minute > 59) {
                DEException.throwException(Translator.get("ee_report_rate_invalid"));
            }
            return "0 " + minute + " * * * ?";
        }
        return fakeTimeCron(rateType, rateValue);
    }

    private static String fakeTimeCron(int rateType, String value) {
        Matcher matcher = value == null ? null : FAKE_TIME.matcher(value);
        if (matcher == null || !matcher.matches()) {
            DEException.throwException(Translator.get("ee_report_rate_invalid"));
            return null;
        }
        int day = Integer.parseInt(matcher.group(1));
        int hour = Integer.parseInt(matcher.group(2));
        int minute = Integer.parseInt(matcher.group(3));
        int second = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        if (hour > 23 || minute > 59 || second > 59) {
            DEException.throwException(Translator.get("ee_report_rate_invalid"));
        }
        String time = second + " " + minute + " " + hour;
        return switch (rateType) {
            case 1 -> time + " * * ?";
            case 2 -> {
                if (day < 1 || day > 7) {
                    DEException.throwException(Translator.get("ee_report_rate_invalid"));
                }
                yield time + " ? * " + QUARTZ_WEEK[day - 1];
            }
            case 3 -> {
                if (day < 1 || day > 31) {
                    DEException.throwException(Translator.get("ee_report_rate_invalid"));
                }
                // 31 号按“每月最后一天”执行，避免小月不执行
                yield time + " " + (day == 31 ? "L" : String.valueOf(day)) + " * ?";
            }
            default -> {
                DEException.throwException(Translator.get("ee_report_rate_invalid"));
                yield null;
            }
        };
    }

    private static String validCron(String cron) {
        if (StringUtils.isBlank(cron) || !CronExpression.isValidExpression(cron.trim())) {
            DEException.throwException(Translator.get("ee_report_cron_invalid"));
        }
        return cron.trim();
    }

    /**
     * 下次执行时间；没有时返回 null
     */
    public static Long nextFireTime(String cron, Long startTime, Long endTime) {
        try {
            CronExpression expression = new CronExpression(cron);
            long now = System.currentTimeMillis();
            long from = Math.max(startTime == null ? now : startTime - 1000, now);
            Date next = expression.getNextValidTimeAfter(new Date(from));
            if (next == null || (endTime != null && endTime > 0 && next.getTime() > endTime)) {
                return null;
            }
            return next.getTime();
        } catch (Exception e) {
            return null;
        }
    }
}
