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
        DictionaryColumnData[] dictionaries = new DictionaryColumnData[FieldId.FIELD_COUNT];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new ColumnData(FieldId.isMaskField(i) ? maskPool : rawPool, expectedRows);
            if (properties.isDictionaryEnabled() && FieldId.shouldCache(i)) {
                dictionaries[i] = new DictionaryColumnData(rawPool.array(), expectedRows);
            }
        }

        byte[] line = new byte[4096];
        int row = 0;
        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(Paths.get(properties.getDatasetPath())), 1 << 20)) {
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
                parseLine(row, line, len, columns, dictionaries);
                row++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load dataset: " + properties.getDatasetPath(), e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        log.info("Loaded dataset rows={}, rawPoolUsed={}, maskPoolUsed={}, millis={}", row, rawPool.position(), maskPool.position(), millis);
        for (int i = 0; i < dictionaries.length; i++) {
            if (dictionaries[i] != null) {
                log.info("Dictionary field={}, uniqueValues={}", i, dictionaries[i].uniqueCount());
            }
        }
        return new LoadedData(columns, dictionaries, row);
    }

    private void parseLine(int row, byte[] line, int length, ColumnData[] columns, DictionaryColumnData[] dictionaries) {
        int field = 0;
        int index = 0;
        byte[] unquoted = null;
        while (index <= length) {
            if (field >= FieldId.FIELD_COUNT) {
                throw new IllegalStateException("Too many CSV fields at row " + row);
            }

            byte[] valueBytes;
            int valueOffset;
            int valueLength;
            if (index < length && line[index] == '"') {
                if (unquoted == null) {
                    unquoted = new byte[length];
                }
                int[] parsed = parseQuotedField(line, length, index, unquoted, row);
                valueBytes = unquoted;
                valueOffset = 0;
                valueLength = parsed[0];
                index = parsed[1];
            } else {
                int start = index;
                while (index < length && line[index] != ',') {
                    index++;
                }
                valueBytes = line;
                valueOffset = start;
                valueLength = index - start;
                index++;
            }

            appendField(row, field, valueBytes, valueOffset, valueLength, columns, dictionaries);
            field++;
        }
        if (field != FieldId.FIELD_COUNT) {
            throw new IllegalStateException("Unexpected CSV field count at row " + row + ": " + field);
        }
    }

    private int[] parseQuotedField(byte[] line, int length, int index, byte[] output, int row) {
        int read = index + 1;
        int write = 0;
        while (read < length) {
            byte current = line[read++];
            if (current == '"') {
                if (read < length && line[read] == '"') {
                    output[write++] = '"';
                    read++;
                    continue;
                }
                if (read < length && line[read] == ',') {
                    read++;
                } else if (read != length) {
                    throw new IllegalStateException("Invalid quoted CSV field at row " + row);
                } else {
                    read++;
                }
                return new int[]{write, read};
            }
            output[write++] = current;
        }
        throw new IllegalStateException("Unclosed quoted CSV field at row " + row);
    }

    private void appendField(int row, int field, byte[] valueBytes, int valueOffset, int valueLength,
                             ColumnData[] columns, DictionaryColumnData[] dictionaries) {
        if (FieldId.isMaskField(field)) {
            byte[] masked = maskingService.mask(valueBytes, valueOffset, valueLength);
            columns[field].put(row, masked);
        } else {
            int poolOffset = columns[field].put(row, valueBytes, valueOffset, valueLength);
            DictionaryColumnData dictionary = dictionaries[field];
            if (dictionary != null) {
                dictionary.addRowValue(row, valueBytes, valueOffset, valueLength, poolOffset);
            }
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
