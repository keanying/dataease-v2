package io.dataease.ee.sync.engine;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.dataease.ee.sync.provider.DorisStreamLoader;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Doris Stream Load 写入：每批序列化为 JSON 数组导入一次（一个事务、一个 label）。
 *
 * <p>FE / BE 不可达（或端口不是 Doris HTTP 服务）时回退到 JDBC 批量写入，后续批次继续使用 JDBC。
 * 容错率映射为 {@code max_filter_ratio}，被过滤的行计为失败行。</p>
 */
public class StreamLoadRowWriter implements SyncRowWriter {

    /**
     * 每批最多行数 / 估算字节数
     */
    public static final int BATCH_ROWS = 50_000;
    public static final long BATCH_BYTES = 64L * 1024 * 1024;

    private static final JsonFactory JSON = new JsonFactory();
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final DorisStreamLoader loader;
    private final String table;
    private final List<String> columns;
    private final double tolerance;
    private final SyncJobContext context;
    private final Consumer<String> log;
    private final Callable<SyncRowWriter> fallbackFactory;
    private final String labelPrefix;

    private SyncRowWriter fallback;
    private int sequence = 0;
    private long loadedBatches = 0;

    public StreamLoadRowWriter(DorisStreamLoader loader, String table, List<String> columns, double tolerance,
                               SyncJobContext context, Consumer<String> log, Callable<SyncRowWriter> fallbackFactory) {
        this.loader = loader;
        this.table = table;
        this.columns = columns;
        this.tolerance = tolerance;
        this.context = context;
        this.log = log;
        this.fallbackFactory = fallbackFactory;
        // label 只允许字母数字、- 与 _，长度不超过 128
        this.labelPrefix = "de_sync_" + context.getTaskId() + "_" + context.getLogId() + "_" + Long.toString(System.currentTimeMillis(), 36);
    }

    @Override
    public int batchRows() {
        return fallback != null ? fallback.batchRows() : BATCH_ROWS;
    }

    @Override
    public long batchBytes() {
        return fallback != null ? fallback.batchBytes() : BATCH_BYTES;
    }

    public boolean isFallback() {
        return fallback != null;
    }

    @Override
    public long write(List<Object[]> batch) throws Exception {
        if (fallback != null) {
            return fallback.write(batch);
        }
        byte[] body = serialize(batch);
        String label = labelPrefix + "_" + (++sequence);
        DorisStreamLoader.Result result;
        try {
            result = load(body, label);
        } catch (DorisStreamLoader.UnavailableException e) {
            if (context.isCancelled()) {
                throw e;
            }
            log.accept(Translator.get("ee_sync_log_stream_load_fallback", e.getMessage()));
            fallback = fallbackFactory.call();
            return fallback.write(batch);
        } catch (DorisStreamLoader.LoadException e) {
            String detail = e.getMessage() + (e.getErrorUrl() == null ? "" : " ErrorURL: " + e.getErrorUrl());
            throw new IllegalStateException(Translator.get("ee_sync_log_stream_load_failed", label, detail), e);
        }
        loadedBatches++;
        if (loadedBatches == 1) {
            log.accept(Translator.get("ee_sync_log_stream_load", loader.getFeBase(), StringUtils.defaultString(result.beHost())));
        }
        if (result.filteredRows() > 0) {
            log.accept(Translator.get("ee_sync_log_stream_load_filtered", label, String.valueOf(result.filteredRows()),
                    StringUtils.defaultString(result.errorUrl())));
        }
        return result.filteredRows();
    }

    /**
     * 导入一批；网络中断时以同一 label 重试一次（已导入时服务端返回 Label Already Exists）
     */
    private DorisStreamLoader.Result load(byte[] body, String label) throws IOException {
        try {
            return loader.load(table, columns, body, label, tolerance, context);
        } catch (DorisStreamLoader.UnavailableException e) {
            if (loadedBatches == 0 || context.isCancelled()) {
                throw e;
            }
            return loader.load(table, columns, body, label, tolerance, context);
        }
    }

    byte[] serialize(List<Object[]> batch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, batch.size() * 64));
        try (JsonGenerator gen = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            gen.writeStartArray();
            for (Object[] row : batch) {
                gen.writeStartObject();
                for (int i = 0; i < columns.size(); i++) {
                    gen.writeFieldName(columns.get(i));
                    writeValue(gen, i < row.length ? row[i] : null);
                }
                gen.writeEndObject();
            }
            gen.writeEndArray();
        }
        return out.toByteArray();
    }

    /**
     * 值序列化：数值（num_as_string 解析）、布尔 1/0、时间 yyyy-MM-dd HH:mm:ss.SSSSSS、二进制 Base64，其余文本
     */
    static void writeValue(JsonGenerator gen, Object value) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof Boolean b) {
            gen.writeNumber(b ? 1 : 0);
        } else if (value instanceof BigDecimal d) {
            gen.writeString(d.toPlainString());
        } else if (value instanceof BigInteger bi) {
            gen.writeString(bi.toString());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                gen.writeNull();
            } else {
                gen.writeNumber(d);
            }
        } else if (value instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                gen.writeNull();
            } else {
                gen.writeNumber(f);
            }
        } else if (value instanceof Number n) {
            gen.writeNumber(n.longValue());
        } else if (value instanceof Timestamp ts) {
            gen.writeString(ts.toLocalDateTime().format(DATETIME));
        } else if (value instanceof java.sql.Date date) {
            gen.writeString(date.toLocalDate().toString());
        } else if (value instanceof Time time) {
            gen.writeString(time.toLocalTime().toString());
        } else if (value instanceof LocalDateTime ldt) {
            gen.writeString(ldt.format(DATETIME));
        } else if (value instanceof LocalDate date) {
            gen.writeString(date.toString());
        } else if (value instanceof LocalTime time) {
            gen.writeString(time.toString());
        } else if (value instanceof OffsetDateTime odt) {
            gen.writeString(odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().format(DATETIME));
        } else if (value instanceof java.util.Date date) {
            gen.writeString(new Timestamp(date.getTime()).toLocalDateTime().format(DATETIME));
        } else if (value instanceof byte[] bytes) {
            gen.writeString(Base64.getEncoder().encodeToString(bytes));
        } else {
            gen.writeString(value.toString());
        }
    }

    /**
     * 估算一行的序列化字节数（用于按字节分批）
     */
    public static long estimate(Object[] row) {
        long size = 2;
        for (Object value : row) {
            if (value instanceof CharSequence cs) {
                size += cs.length() * 3L + 8;
            } else if (value instanceof byte[] bytes) {
                size += bytes.length * 4L / 3 + 8;
            } else {
                size += 24;
            }
        }
        return size;
    }

    @Override
    public void close() throws Exception {
        try {
            loader.close();
        } finally {
            if (fallback != null) {
                fallback.close();
            }
        }
    }
}
