package com.dcc.store;

import com.dcc.config.AppProperties;
import com.dcc.domain.FieldId;
import com.dcc.service.MaskingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

@Component
public class DataStore {
    private static final Logger log = LoggerFactory.getLogger(DataStore.class);
    private final AppProperties properties;
    private final MaskingService maskingService;
    private volatile CompletableFuture<LoadedData> loadedFuture;

    public DataStore(AppProperties properties, MaskingService maskingService) {
        this.properties = properties;
        this.maskingService = maskingService;
    }

    public LoadedData get() {
        CompletableFuture<LoadedData> future = loadedFuture;
        if (future == null) {
            synchronized (this) {
                future = loadedFuture;
                if (future == null) {
                    // 首个 /encrypt 才触发加载；/health 前不能做任何加密业务相关动作。
                    // CompletableFuture 被 volatile 字段保存，100 个并发请求会等待同一份加载结果，避免重复读盘。
                    future = CompletableFuture.supplyAsync(this::load);
                    loadedFuture = future;
                }
            }
        }
        return future.join();
    }

    private LoadedData load() {
        long start = System.nanoTime();
        int expectedRows = properties.getExpectedRows();
        BytePool rawPool = new BytePool(properties.getInitialRawPoolBytes());
        BytePool maskPool = new BytePool(properties.getInitialMaskPoolBytes());
        ColumnData[] columns = new ColumnData[FieldId.FIELD_COUNT];
        for (int i = 0; i < columns.length; i++) {
            // SM4 字段进入 rawPool 保存原文；掩码字段进入 maskPool 保存最终脱敏结果。
            // 所有列共用两个预分配池，避免 30 万行 * 11 字段产生大量 byte[] 或 String。
            columns[i] = new ColumnData(FieldId.isMaskField(i) ? maskPool : rawPool, expectedRows);
        }

        // 单行缓冲固定为 4KB，匹配题目字段最大长度；如果正式数据行更长，应调大该常量而不是运行时扩容。
        byte[] line = new byte[4096];
        int row = 0;
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(Paths.get(properties.getDatasetPath())), 1 << 20)) {
            // 第一行是 CSV 表头，直接跳过；正式数据字段顺序按 README 固定。
            int len = readLine(in, line);
            if (len < 0) {
                throw new IllegalStateException("Dataset is empty");
            }
            while ((len = readLine(in, line)) >= 0) {
                if (len == 0) {
                    continue;
                }
                if (row >= expectedRows) {
                    throw new IllegalStateException("Dataset row count exceeds dcc.expectedRows");
                }
                parseLine(row, line, len, columns);
                row++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load dataset: " + properties.getDatasetPath(), e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        log.info("Loaded dataset rows={}, rawPoolUsed={}, maskPoolUsed={}, millis={}", row, rawPool.position(), maskPool.position(), millis);
        return new LoadedData(columns, row);
    }

    private void parseLine(int row, byte[] line, int length, ColumnData[] columns) {
        int field = 0;
        int start = 0;
        // 题目数据无复杂 CSV 转义，使用逗号扫描比通用 CSV 解析器更少对象、更少分支。
        for (int i = 0; i <= length; i++) {
            if (i == length || line[i] == ',') {
                if (field >= FieldId.FIELD_COUNT) {
                    throw new IllegalStateException("Too many CSV fields at row " + row);
                }
                int fieldLen = i - start;
                if (FieldId.isMaskField(field)) {
                    // 掩码字段与请求密钥无关，加载时预计算一次，之后所有请求直接写结果。
                    byte[] masked = maskingService.mask(line, start, fieldLen);
                    columns[field].put(row, masked);
                } else {
                    // SM4 字段必须随请求密钥变化，只能保存原始 UTF-8 字节，不能提前加密。
                    columns[field].put(row, line, start, fieldLen);
                }
                field++;
                start = i + 1;
            }
        }
        if (field != FieldId.FIELD_COUNT) {
            throw new IllegalStateException("Unexpected CSV field count at row " + row + ": " + field);
        }
    }

    private int readLine(BufferedInputStream in, byte[] buffer) throws IOException {
        int pos = 0;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                if (pos == buffer.length) {
                    // 不做自动扩容，避免在数据异常时把问题变成不可控的内存峰值。
                    throw new IllegalStateException("CSV line exceeds fixed parser buffer");
                }
                buffer[pos++] = (byte) b;
            }
        }
        if (b < 0 && pos == 0) {
            return -1;
        }
        return pos;
    }
}
