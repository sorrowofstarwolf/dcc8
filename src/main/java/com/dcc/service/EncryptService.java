package com.dcc.service;

import com.dcc.config.AppProperties;
import com.dcc.crypto.FixedCipherCache;
import com.dcc.crypto.Hashing;
import com.dcc.crypto.Sm4Cipher;
import com.dcc.domain.FieldId;
import com.dcc.model.CallbackRequest;
import com.dcc.model.EncryptRequest;
import com.dcc.store.ColumnData;
import com.dcc.store.DataStore;
import com.dcc.store.DictionaryColumnData;
import com.dcc.store.LoadedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class EncryptService {
    private static final Logger log = LoggerFactory.getLogger(EncryptService.class);
    private final AppProperties properties;
    private final DataStore dataStore;
    private final Sm4Cipher sm4Cipher;
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor executor;

    public EncryptService(AppProperties properties, DataStore dataStore, Sm4Cipher sm4Cipher) {
        this.properties = properties;
        this.dataStore = dataStore;
        this.sm4Cipher = sm4Cipher;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeoutMillis());
        requestFactory.setReadTimeout(properties.getRequestTimeoutMillis());
        this.restTemplate = new RestTemplate(requestFactory);
        // 固定小线程池用于削峰：HTTP 可以同时进来 100 个请求，但真正执行加密的线程保持在配置值。
        // 这样可以减少线程切换、GC 压力和磁盘写入争抢，通常比 100 个任务同时跑更快。
        this.executor = new ThreadPoolExecutor(
                properties.getWorkerThreads(),
                properties.getWorkerThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "dcc-encrypt-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void submit(EncryptRequest request) {
        // 请求字段最多 7 个，直接使用固定长度 int[] 保存字段编号，避免热路径 List/Map 分配。
        int[] fields = new int[FieldId.MAX_REQUEST_FIELDS];
        String[] names = request.getFieldsToEncrypt();
        if (names == null || names.length == 0 || names.length > FieldId.MAX_REQUEST_FIELDS) {
            throw new IllegalArgumentException("fieldsToEncrypt length must be 1.." + FieldId.MAX_REQUEST_FIELDS);
        }
        for (int i = 0; i < names.length; i++) {
            fields[i] = FieldId.fromName(names[i]);
        }
        // 任务对象是每个请求允许创建的少量对象之一；真正的大量行处理在任务内部复用缓冲区。
        executor.execute(new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length));
    }

    public Path generateBlocking(EncryptRequest request) {
        int[] fields = new int[FieldId.MAX_REQUEST_FIELDS];
        String[] names = request.getFieldsToEncrypt();
        for (int i = 0; i < names.length; i++) {
            fields[i] = FieldId.fromName(names[i]);
        }
        return new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length).call(false);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    private final class EncryptTask implements Runnable {
        private final String requestId;
        private final String sm4Key;
        private final String ip;
        private final int[] fields;
        private final int fieldCount;

        private EncryptTask(String requestId, String sm4Key, String ip, int[] fields, int fieldCount) {
            this.requestId = requestId;
            this.sm4Key = sm4Key;
            this.ip = ip;
            this.fields = fields;
            this.fieldCount = fieldCount;
        }

        @Override
        public void run() {
            call(true);
        }

        private Path call(boolean doCallback) {
            long start = System.nanoTime();
            // 首次请求会在这里触发 DataStore 惰性加载；后续请求拿到同一个 LoadedData。
            LoadedData data = dataStore.get();
            Path output = Paths.get(properties.getOutputDir(), requestId + ".csv");
            try {
                Files.createDirectories(output.getParent());
                writeFile(data, output);
                if (doCallback) {
                    // 文件写完并关闭后再回调，避免验证程序读到半写入文件。
                    callback();
                }
                long millis = (System.nanoTime() - start) / 1_000_000L;
                log.info("requestId={}, fields={}, keyLength={}, rows={}, millis={}", requestId, fieldCount, sm4Key == null ? 0 : sm4Key.length(), data.rows(), millis);
                return output;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write output file for " + requestId, e);
            }
        }

        private void writeFile(LoadedData data, Path output) throws IOException {
            // 每个请求一个 SM4 Context，因为 sm4Key 随请求变化；同一请求内复用该 Context。
            Sm4Cipher.Context cipher = sm4Cipher.newContext(sm4Key);
            RequestEncryptedDictionary[] encryptedDictionaries = buildEncryptedDictionaries(data, cipher);
            // caches 按字段懒创建，仅低基数字段启用，避免高基数字段缓存开销超过收益。
            FixedCipherCache[] caches = new FixedCipherCache[FieldId.FIELD_COUNT];
            // cellBuffer/tempBuffer 是请求级复用缓冲，避免每个单元格创建密文数组或 HEX 字符串。
            byte[] cellBuffer = new byte[256];
            byte[] tempBuffer = new byte[256];
            try (OutputStream out = Files.newOutputStream(output)) {
                FastCsvWriter writer = new FastCsvWriter(out, properties.getOutputBufferBytes());
                for (int row = 0; row < data.rows(); row++) {
                    if (row > 0) {
                        // baseline 使用 CRLF 且文件末尾不额外追加空行。
                        writer.writeByte('\r');
                        writer.writeByte('\n');
                    }
                    for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                        if (fieldIndex > 0) {
                            writer.writeByte(',');
                        }
                        int fieldId = fields[fieldIndex];
                        ColumnData column = data.column(fieldId);
                        byte[] bytes = column.bytes();
                        int offset = column.offset(row);
                        int length = column.length(row);
                        if (FieldId.isMaskField(fieldId)) {
                            // 掩码字段已经在加载时预计算，直接从列式字节池写出。
                            writer.writeBytes(bytes, offset, length);
                        } else if (encryptedDictionaries[fieldId] != null) {
                            RequestEncryptedDictionary encryptedDictionary = encryptedDictionaries[fieldId];
                            DictionaryColumnData dictionary = data.dictionary(fieldId);
                            int valueId = dictionary.rowValueId(row);
                            writer.writeBytes(encryptedDictionary.bytes, encryptedDictionary.offsets[valueId], encryptedDictionary.lengths[valueId]);
                        } else {
                            // SM4 字段按当前请求密钥即时加密，密文直接写入输出流。
                            int hexLength = encryptCell(fieldId, cipher, caches, bytes, offset, length, cellBuffer, tempBuffer);
                            writer.writeBytes(cellBuffer, 0, hexLength);
                        }
                    }
                }
                // 验证程序收到回调后会立即读文件，因此必须先 flush，并依靠 try-with-resources 完成 close。
                writer.flush();
            }
        }

        private RequestEncryptedDictionary[] buildEncryptedDictionaries(LoadedData data, Sm4Cipher.Context cipher) {
            RequestEncryptedDictionary[] encrypted = new RequestEncryptedDictionary[FieldId.FIELD_COUNT];
            for (int i = 0; i < fieldCount; i++) {
                int fieldId = fields[i];
                if (data.hasDictionary(fieldId)) {
                    encrypted[fieldId] = encryptDictionary(data.dictionary(fieldId), cipher);
                }
            }
            return encrypted;
        }

        private RequestEncryptedDictionary encryptDictionary(DictionaryColumnData dictionary, Sm4Cipher.Context cipher) {
            int uniqueCount = dictionary.uniqueCount();
            int[] offsets = new int[uniqueCount];
            int[] lengths = new int[uniqueCount];
            int totalBytes = 0;
            for (int i = 0; i < uniqueCount; i++) {
                totalBytes += encryptedHexLength(dictionary.uniqueLength(i));
            }
            byte[] encryptedBytes = new byte[totalBytes];
            int position = 0;
            byte[] source = dictionary.bytes();
            for (int i = 0; i < uniqueCount; i++) {
                offsets[i] = position;
                int hexLength = cipher.encryptToHex(source, dictionary.uniqueOffset(i), dictionary.uniqueLength(i), encryptedBytes, position);
                lengths[i] = hexLength;
                position += hexLength;
            }
            return new RequestEncryptedDictionary(encryptedBytes, offsets, lengths);
        }

        private int encryptedHexLength(int plainLength) {
            return ((plainLength / 16) + 1) * 32;
        }

        private final class RequestEncryptedDictionary {
            private final byte[] bytes;
            private final int[] offsets;
            private final int[] lengths;

            private RequestEncryptedDictionary(byte[] bytes, int[] offsets, int[] lengths) {
                this.bytes = bytes;
                this.offsets = offsets;
                this.lengths = lengths;
            }
        }

        private int encryptCell(int fieldId, Sm4Cipher.Context cipher, FixedCipherCache[] caches, byte[] bytes, int offset, int length, byte[] output, byte[] temp) {
            if (!FieldId.shouldCache(fieldId)) {
                // 高基数字段重复率低，直接加密通常比查缓存更划算。
                return cipher.encryptToHex(bytes, offset, length, output, 0);
            }
            FixedCipherCache cache = caches[fieldId];
            if (cache == null) {
                int valueBytes = Math.max(1024 * 1024, properties.getCacheCapacity() * 64);
                // 缓存容量一次性确定，不使用 HashMap，避免节点对象和运行期扩容。
                cache = new FixedCipherCache(properties.getCacheCapacity(), valueBytes);
                caches[fieldId] = cache;
            }
            int hash = Hashing.hash(bytes, offset, length);
            int cached = cache.get(bytes, offset, length, hash, output, 0);
            if (cached >= 0) {
                // 同一请求、同一密钥下，相同明文的密文完全一致，命中后直接复制 HEX。
                return cached;
            }
            int hexLength = cipher.encryptToHex(bytes, offset, length, temp, 0);
            System.arraycopy(temp, 0, output, 0, hexLength);
            // 只在同一请求内缓存；不同请求 sm4Key 可能不同，不能跨请求复用密文。
            cache.put(bytes, offset, length, hash, temp, 0, hexLength);
            return hexLength;
        }

        private void callback() {
            String callbackUrl = properties.getCallbackUrl();
            if (callbackUrl == null || callbackUrl.isEmpty()) {
                return;
            }
            restTemplate.postForObject(callbackUrl, new CallbackRequest(properties.getTeamCode(), requestId, ip), String.class);
        }
    }
}
