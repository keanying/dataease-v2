package io.dataease.ee.log;

import io.dataease.constant.LogOT;
import io.dataease.constant.LogST;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 操作日志的资源类型（{@link LogST}）与操作类型（{@link LogOT}）字典。
 *
 * <p>SDK 的 LogOT 没有“登出”，本模块以 {@link #OT_LOGOUT} 扩展；文案键为
 * {@code ee_log_<枚举 name>}，位于 {@code i18n/ee_log*.properties}。</p>
 */
public final class EeLogTypes {

    private EeLogTypes() {
    }

    /**
     * 登出（SDK LogOT 中没有该值）
     */
    public static final int OT_LOGOUT = 28;

    public static final String CLIENT_PC = "pc";

    public static final String CLIENT_MOBILE = "mobile";

    /**
     * 每种资源类型下可选的操作类型（操作类型筛选树），实际出现过的组合会在查询时补充
     */
    private static final Map<Integer, List<Integer>> SOURCE_OPS = new LinkedHashMap<>();

    static {
        List<Integer> visual = ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.READ, LogOT.EXPORT);
        SOURCE_OPS.put(LogST.PANEL.getValue(), visual);
        SOURCE_OPS.put(LogST.SCREEN.getValue(), visual);
        SOURCE_OPS.put(LogST.DATASET.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.EXPORT));
        SOURCE_OPS.put(LogST.DATASOURCE.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE));
        List<Integer> user = ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.LOGIN);
        user.add(OT_LOGOUT);
        SOURCE_OPS.put(LogST.USER.getValue(), user);
        SOURCE_OPS.put(LogST.ROLE.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.BIND, LogOT.UNBIND));
        SOURCE_OPS.put(LogST.ORG.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE));
        SOURCE_OPS.put(LogST.LINK.getValue(), ops(LogOT.CREATELINK, LogOT.MODIFYLINK, LogOT.DELETELINK));
        SOURCE_OPS.put(LogST.DRIVER.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE));
        SOURCE_OPS.put(LogST.DRIVER_FILE.getValue(), ops(LogOT.UPLOADFILE, LogOT.DELETE));
        SOURCE_OPS.put(LogST.APIKEY.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE));
        SOURCE_OPS.put(LogST.DATA_FILLING.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.EXPORT));
        SOURCE_OPS.put(LogST.DATA.getValue(), ops(LogOT.DOWNLOAD));
        SOURCE_OPS.put(LogST.SPREADSHEET.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.EXPORT));
        SOURCE_OPS.put(LogST.AUTHORIZATION.getValue(), ops(LogOT.AUTHORIZE, LogOT.UNAUTHORIZE));
        SOURCE_OPS.put(LogST.REPORT_TASK.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.TASK_ENABLE,
                LogOT.TASK_DISENABLE, LogOT.TASK_RUN_IMMEDIATELY, LogOT.CLEAR));
        SOURCE_OPS.put(LogST.SYNC_DATASOURCE.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE));
        SOURCE_OPS.put(LogST.SYNC_TASK.getValue(), ops(LogOT.CREATE, LogOT.MODIFY, LogOT.DELETE, LogOT.SYNC_TASK_ENABLE,
                LogOT.SYNC_TASK_DISENABLE, LogOT.SYNC_TASK_RUN_IMMEDIATELY, LogOT.SYNC_TASK_RUN_TERMINATION));
        SOURCE_OPS.put(LogST.SYNC_TASK_LOG.getValue(), ops(LogOT.DELETE, LogOT.CLEAR));
    }

    private static List<Integer> ops(LogOT... ots) {
        List<Integer> list = new ArrayList<>();
        for (LogOT ot : ots) {
            list.add(ot.getValue());
        }
        return list;
    }

    /**
     * 资源类型 → 操作类型（副本，按定义顺序）
     */
    public static Map<Integer, List<Integer>> sourceOps() {
        Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
        SOURCE_OPS.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return copy;
    }

    public static String sourceLabel(Integer st) {
        if (st == null) {
            return "";
        }
        LogST item = Arrays.stream(LogST.values()).filter(v -> v.getValue().equals(st)).findFirst().orElse(null);
        return item == null ? String.valueOf(st) : Translator.get("ee_log_" + item.getName());
    }

    public static String operateLabel(Integer ot) {
        if (ot == null) {
            return "";
        }
        if (ot == OT_LOGOUT) {
            return Translator.get("ee_log_OPERATE_TYPE_LOGOUT");
        }
        LogOT item = Arrays.stream(LogOT.values()).filter(v -> v.getValue().equals(ot)).findFirst().orElse(null);
        return item == null ? String.valueOf(ot) : Translator.get("ee_log_" + item.getName());
    }

    /**
     * 列表中“操作类型”列的文案，如“新建仪表板”
     */
    public static String opText(Integer st, Integer ot) {
        if (st != null && st.equals(LogST.USER.getValue()) && ot != null
                && (ot.equals(LogOT.LOGIN.getValue()) || ot == OT_LOGOUT)) {
            return operateLabel(ot);
        }
        return Translator.get("ee_log_op_text", operateLabel(ot), sourceLabel(st));
    }

    /**
     * 根据 {@code @DeLog.stExp} 的取值解析资源类型；无法识别时返回 null
     */
    public static LogST parseSource(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LogST st) {
            return st;
        }
        if (value instanceof Number number) {
            int v = number.intValue();
            return Arrays.stream(LogST.values()).filter(item -> item.getValue() == v).findFirst().orElse(null);
        }
        String text = StringUtils.trimToEmpty(value.toString()).toLowerCase();
        return switch (text) {
            case "dashboard", "panel" -> LogST.PANEL;
            case "datav", "screen" -> LogST.SCREEN;
            case "dataset" -> LogST.DATASET;
            case "datasource" -> LogST.DATASOURCE;
            case "data_filling", "datafilling", "data-filling" -> LogST.DATA_FILLING;
            case "spreadsheet" -> LogST.SPREADSHEET;
            case "view", "chart" -> LogST.VIEW;
            default -> {
                if (StringUtils.isNumeric(text)) {
                    yield parseSource(Integer.parseInt(text));
                }
                yield Arrays.stream(LogST.values()).filter(item -> item.name().equalsIgnoreCase(text)).findFirst().orElse(null);
            }
        };
    }
}
