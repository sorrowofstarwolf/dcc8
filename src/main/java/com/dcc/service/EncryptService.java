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
import java.util.concurrent.Semaphore;
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
    private final ThreadPoolExecutor callbackExecutor;
    private final Semaphore writePermits;

    public EncryptService(AppProperties properties, DataStore dataStore, Sm4Cipher sm4Cipher) {
        this.properties = properties;
        this.dataStore = dataStore;
        this.sm4Cipher = sm4Cipher;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeoutMillis());
        requestFactory.setReadTimeout(properties.getRequestTimeoutMillis());
        this.restTemplate = new RestTemplate(requestFactory);
        this.writePermits = new Semaphore(Math.max(1, properties.getWritePermits()));
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
        this.callbackExecutor = new ThreadPoolExecutor(
                properties.getCallbackThreads(),
                properties.getCallbackThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getCallbackQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "dcc-callback-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void submit(EncryptRequest request) {
        int[] fields = new int[FieldId.MAX_REQUEST_FIELDS];
        String[] names = request.getFieldsToEncrypt();
        if (names == null || names.length == 0 || names.length > FieldId.MAX_REQUEST_FIELDS) {
            throw new IllegalArgumentException("fieldsToEncrypt length must be 1.." + FieldId.MAX_REQUEST_FIELDS);
        }
        for (int i = 0; i < names.length; i++) {
            fields[i] = FieldId.fromName(names[i]);
        }
        executor.execute(new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length));
    }

    public Path generateBlocking(EncryptRequest request) {
        int[] fields = new int[FieldId.MAX_REQUEST_FIELDS];
        String[] names = request.getFieldsToEncrypt();
        if (names == null || names.length == 0 || names.length > FieldId.MAX_REQUEST_FIELDS) {
            throw new IllegalArgumentException("fieldsToEncrypt length must be 1.." + FieldId.MAX_REQUEST_FIELDS);
        }
        for (int i = 0; i < names.length; i++) {
            fields[i] = FieldId.fromName(names[i]);
        }
        return new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length).call(false);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        callbackExecutor.shutdown();
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
            LoadedData data = dataStore.get();
            Path output = Paths.get(properties.getOutputDir(), requestId + ".csv");
            try {
                Files.createDirectories(output.getParent());
                writeFile(data, output);
                if (doCallback) {
                    submitCallback();
                }
                long millis = (System.nanoTime() - start) / 1_000_000L;
                log.info("requestId={}, fields={}, keyLength={}, rows={}, millis={}", requestId, fieldCount, sm4Key == null ? 0 : sm4Key.length(), data.rows(), millis);
                return output;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write output file for " + requestId, e);
            }
        }

        private void writeFile(LoadedData data, Path output) throws IOException {
            Sm4Cipher.Context cipher = sm4Cipher.newContext(sm4Key);
            RequestEncryptedDictionary[] encryptedDictionaries = buildEncryptedDictionaries(data, cipher);
            FixedCipherCache[] caches = new FixedCipherCache[FieldId.FIELD_COUNT];
            int outputLength = estimateOutputLength(data, encryptedDictionaries);
            if (outputLength <= properties.getMaxBufferedOutputBytes()) {
                writeBuffered(data, output, cipher, encryptedDictionaries, caches, outputLength);
            } else {
                writeStreaming(data, output, cipher, encryptedDictionaries, caches);
            }
        }

        private void writeBuffered(LoadedData data, Path output, Sm4Cipher.Context cipher,
                                   RequestEncryptedDictionary[] encryptedDictionaries, FixedCipherCache[] caches,
                                   int outputLength) throws IOException {
            byte[] tempBuffer = new byte[256];
            byte[] outputBytes = new byte[outputLength];
            int position = 0;
            for (int row = 0; row < data.rows(); row++) {
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    if (fieldIndex > 0) {
                        outputBytes[position++] = ',';
                    }
                    int fieldId = fields[fieldIndex];
                    ColumnData column = data.column(fieldId);
                    byte[] bytes = column.bytes();
                    int offset = column.offset(row);
                    int length = column.length(row);
                    if (FieldId.isMaskField(fieldId)) {
                        System.arraycopy(bytes, offset, outputBytes, position, length);
                        position += length;
                    } else if (encryptedDictionaries[fieldId] != null) {
                        RequestEncryptedDictionary encryptedDictionary = encryptedDictionaries[fieldId];
                        DictionaryColumnData dictionary = data.dictionary(fieldId);
                        int valueId = dictionary.rowValueId(row);
                        int encryptedOffset = encryptedDictionary.offsets[valueId];
                        int encryptedLength = encryptedDictionary.lengths[valueId];
                        System.arraycopy(encryptedDictionary.bytes, encryptedOffset, outputBytes, position, encryptedLength);
                        position += encryptedLength;
                    } else {
                        int hexLength = encryptCell(fieldId, cipher, caches, bytes, offset, length, outputBytes, position, tempBuffer);
                        position += hexLength;
                    }
                }
                outputBytes[position++] = '\n';
            }
            try (OutputStream out = throttledOutputStream(output)) {
                out.write(outputBytes);
            }
        }

        private void writeStreaming(LoadedData data, Path output, Sm4Cipher.Context cipher,
                                    RequestEncryptedDictionary[] encryptedDictionaries, FixedCipherCache[] caches)
                throws IOException {
            byte[] cellBuffer = new byte[256];
            byte[] tempBuffer = new byte[256];
            try (OutputStream out = throttledOutputStream(output)) {
                FastCsvWriter writer = new FastCsvWriter(out, properties.getOutputBufferBytes());
                for (int row = 0; row < data.rows(); row++) {
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
                            writer.writeBytes(bytes, offset, length);
                        } else if (encryptedDictionaries[fieldId] != null) {
                            RequestEncryptedDictionary encryptedDictionary = encryptedDictionaries[fieldId];
                            DictionaryColumnData dictionary = data.dictionary(fieldId);
                            int valueId = dictionary.rowValueId(row);
                            writer.writeBytes(encryptedDictionary.bytes, encryptedDictionary.offsets[valueId], encryptedDictionary.lengths[valueId]);
                        } else {
                            int hexLength = encryptCell(fieldId, cipher, caches, bytes, offset, length, cellBuffer, 0, tempBuffer);
                            writer.writeBytes(cellBuffer, 0, hexLength);
                        }
                    }
                    writer.writeByte('\n');
                }
                writer.flush();
            }
        }

        private OutputStream throttledOutputStream(Path output) throws IOException {
            return new WritePermitOutputStream(Files.newOutputStream(output), writePermits);
        }

        private int estimateOutputLength(LoadedData data, RequestEncryptedDictionary[] encryptedDictionaries) {
            long total = (long) data.rows() * fieldCount;
            for (int row = 0; row < data.rows(); row++) {
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    int fieldId = fields[fieldIndex];
                    ColumnData column = data.column(fieldId);
                    int length = column.length(row);
                    if (FieldId.isMaskField(fieldId)) {
                        total += length;
                    } else if (encryptedDictionaries[fieldId] != null) {
                        RequestEncryptedDictionary encryptedDictionary = encryptedDictionaries[fieldId];
                        DictionaryColumnData dictionary = data.dictionary(fieldId);
                        int valueId = dictionary.rowValueId(row);
                        total += encryptedDictionary.lengths[valueId];
                    } else {
                        total += encryptedHexLength(length);
                    }
                }
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("Output file is too large to buffer in one byte array: " + total);
            }
            return (int) total;
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

        private int encryptCell(int fieldId, Sm4Cipher.Context cipher, FixedCipherCache[] caches, byte[] bytes, int offset, int length, byte[] output, int targetOffset, byte[] temp) {
            if (!FieldId.shouldCache(fieldId)) {
                return cipher.encryptToHex(bytes, offset, length, output, targetOffset);
            }
            FixedCipherCache cache = caches[fieldId];
            if (cache == null) {
                int valueBytes = Math.max(1024 * 1024, properties.getCacheCapacity() * 64);
                cache = new FixedCipherCache(properties.getCacheCapacity(), valueBytes);
                caches[fieldId] = cache;
            }
            int hash = Hashing.hash(bytes, offset, length);
            int cached = cache.get(bytes, offset, length, hash, output, targetOffset);
            if (cached >= 0) {
                return cached;
            }
            int hexLength = cipher.encryptToHex(bytes, offset, length, temp, 0);
            System.arraycopy(temp, 0, output, targetOffset, hexLength);
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

        private void submitCallback() {
            String callbackUrl = properties.getCallbackUrl();
            if (callbackUrl == null || callbackUrl.isEmpty()) {
                return;
            }
            callbackExecutor.execute(() -> {
                try {
                    callback();
                } catch (RuntimeException e) {
                    log.warn("callback failed, requestId={}", requestId, e);
                }
            });
        }
    }

    private static final class WritePermitOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final Semaphore permits;

        private WritePermitOutputStream(OutputStream delegate, Semaphore permits) {
            this.delegate = delegate;
            this.permits = permits;
        }

        @Override
        public void write(int b) throws IOException {
            acquire();
            try {
                delegate.write(b);
            } finally {
                permits.release();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            acquire();
            try {
                delegate.write(b, off, len);
            } finally {
                permits.release();
            }
        }

        @Override
        public void flush() throws IOException {
            acquire();
            try {
                delegate.flush();
            } finally {
                permits.release();
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void acquire() throws IOException {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for write permit", e);
            }
        }
    }
}
