package io.dataease.ee.report;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.utils.JsonUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 定时报告常量与 JSON 列表字段工具
 */
public final class ReportConstants {

    private ReportConstants() {
    }

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_STOPPED = 2;
    public static final int STATUS_FINISHED = 3;

    public static final int EXEC_RUNNING = 1;
    public static final int EXEC_SUCCESS = 2;
    public static final int EXEC_FAILED = 3;

    public static final int TRIGGER_SCHEDULE = 0;
    public static final int TRIGGER_MANUAL = 1;
    public static final int TRIGGER_RETRY = 2;

    public static final int RTID_DASHBOARD = 0;
    public static final int RTID_SCREEN = 1;

    public static final int FORMAT_JPEG = 0;
    public static final int FORMAT_PDF = 1;

    /**
     * 前端 websocket 订阅：/user/{uid}/report-notice
     */
    public static final String WS_TOPIC = "/report-notice";

    /**
     * 后台任务按用户语言输出提示信息（默认简体中文）；调用方在 finally 中执行 LocaleContextHolder.resetLocaleContext()
     */
    public static void applyLocale(Long uid) {
        io.dataease.permission.model.V3BaseUser user = uid == null ? null : io.dataease.permission.util.V3UserUtil.getUser(uid);
        String lang = user == null ? null : user.getLanguage();
        java.util.Locale locale;
        if (StringUtils.isBlank(lang)) {
            locale = java.util.Locale.SIMPLIFIED_CHINESE;
        } else if (StringUtils.equalsAnyIgnoreCase(lang, "tw", "zh-TW", "zh_TW")) {
            locale = java.util.Locale.TRADITIONAL_CHINESE;
        } else if (StringUtils.startsWithIgnoreCase(lang, "en")) {
            locale = java.util.Locale.US;
        } else {
            locale = java.util.Locale.SIMPLIFIED_CHINESE;
        }
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
    }

    public static String toJson(List<?> list) {
        if (CollectionUtils.isEmpty(list)) {
            return "[]";
        }
        return JsonUtil.toJSONString(list.stream().filter(Objects::nonNull).toList()).toString();
    }

    public static List<Long> longs(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        List<Long> list = JsonUtil.parseList(json, new TypeReference<>() {
        });
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static List<Integer> ints(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        List<Integer> list = JsonUtil.parseList(json, new TypeReference<>() {
        });
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static List<String> strings(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        List<String> list = JsonUtil.parseList(json, new TypeReference<>() {
        });
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static List<String> idStrings(String json) {
        return longs(json).stream().map(String::valueOf).toList();
    }

    public static List<Long> parseIds(List<String> values) {
        List<Long> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value) && StringUtils.isNumeric(value.trim())) {
                result.add(Long.valueOf(value.trim()));
            }
        }
        return result;
    }
}
