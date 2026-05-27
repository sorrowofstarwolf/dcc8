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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class EncryptService {
    private static final Logger log = LoggerFactory.getLogger(EncryptService.class);
    private static final int MIN_ENCRYPTED_HEX_LENGTH = 32;
    private static final int MAX_DICTIONARY_UNIQUE_PERCENT = 50;
    private static final int HIGH_CARDINALITY_DICTIONARY_ROWS = 100_000;
    private final AppProperties properties;
    private final DataStore dataStore;
    private final Sm4Cipher sm4Cipher;
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor chunkExecutor;
    private final BlockingQueue<EncryptTask> jobQueue;
    private final Semaphore activeHeavyJobSlots;
    private final ThreadPoolExecutor callbackExecutor;
    private final Semaphore writePermits;
    private final ThreadLocal<Sm4Cipher.Context> chunkCipherContext;
    private final ChunkBufferPool chunkBufferPool;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread dispatcherThread;

    public EncryptService(AppProperties properties, DataStore dataStore, Sm4Cipher sm4Cipher) {
        this.properties = properties;
        this.dataStore = dataStore;
        this.sm4Cipher = sm4Cipher;
        this.chunkCipherContext = ThreadLocal.withInitial(() -> this.sm4Cipher.newContext("0000000000000000"));
        this.chunkBufferPool = new ChunkBufferPool(properties.getChunkBufferPoolBytes());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeoutMillis());
        requestFactory.setReadTimeout(properties.getRequestTimeoutMillis());
        this.restTemplate = new RestTemplate(requestFactory);
        this.writePermits = new Semaphore(Math.max(1, properties.getWritePermits()));
        this.jobQueue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        int activeHeavyJobs = properties.getActiveHeavyJobs() > 0
                ? properties.getActiveHeavyJobs()
                : properties.getWorkerThreads();
        this.activeHeavyJobSlots = new Semaphore(Math.max(1, activeHeavyJobs), true);
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
                new ThreadPoolExecutor.AbortPolicy());
        this.chunkExecutor = new ThreadPoolExecutor(
                Math.max(1, properties.getChunkWorkerThreads()),
                Math.max(1, properties.getChunkWorkerThreads()),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getChunkQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "dcc-chunk-worker");
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

    @PostConstruct
    public void startDispatcher() {
        dispatcherThread = new Thread(this::dispatchLoop, "dcc-job-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
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
        boolean accepted = jobQueue.offer(new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length));
        if (!accepted) {
            throw new RejectedExecutionException("encrypt job queue is full");
        }
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
        running.set(false);
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
        }
        executor.shutdown();
        chunkExecutor.shutdown();
        callbackExecutor.shutdown();
    }

    private void dispatchLoop() {
        while (running.get()) {
            boolean permitAcquired = false;
            try {
                EncryptTask task = jobQueue.take();
                activeHeavyJobSlots.acquire();
                permitAcquired = true;
                executor.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        activeHeavyJobSlots.release();
                    }
                });
                permitAcquired = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RejectedExecutionException e) {
                if (permitAcquired) {
                    activeHeavyJobSlots.release();
                }
                log.warn("encrypt executor rejected dispatched job", e);
            } catch (RuntimeException e) {
                if (permitAcquired) {
                    activeHeavyJobSlots.release();
                }
                log.warn("encrypt dispatcher failed", e);
            }
        }
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
            boolean[] cellCacheEnabled = buildCellCachePlan(data, encryptedDictionaries);
            if (properties.isChunkedPipelineEnabled() && isDefinitelyStreamingOutput(data)) {
                writeChunked(data, output, encryptedDictionaries, cellCacheEnabled);
                return;
            }
            int outputLength = -1;
            if (!isDefinitelyStreamingOutput(data)) {
                outputLength = estimateOutputLength(data, encryptedDictionaries);
            }
            if (outputLength >= 0 && outputLength <= properties.getMaxBufferedOutputBytes()) {
                writeBuffered(data, output, cipher, encryptedDictionaries, caches, cellCacheEnabled, outputLength);
            } else {
                writeStreaming(data, output, cipher, encryptedDictionaries, caches, cellCacheEnabled);
            }
        }

        private void writeBuffered(LoadedData data, Path output, Sm4Cipher.Context cipher,
                                   RequestEncryptedDictionary[] encryptedDictionaries, FixedCipherCache[] caches,
                                   boolean[] cellCacheEnabled,
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
                        int hexLength = encryptCell(fieldId, cipher, caches, cellCacheEnabled, bytes, offset, length, outputBytes, position, tempBuffer);
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
                                    RequestEncryptedDictionary[] encryptedDictionaries, FixedCipherCache[] caches,
                                    boolean[] cellCacheEnabled)
                throws IOException {
            try (OutputStream out = throttledOutputStream(output)) {
                FastCsvWriter writer = new FastCsvWriter(out, properties.getOutputBufferBytes());
                writeRows(data, 0, data.rows(), cipher, encryptedDictionaries, caches, cellCacheEnabled, writer);
                writer.flush();
            }
        }

        private void writeChunked(LoadedData data, Path output,
                                  RequestEncryptedDictionary[] encryptedDictionaries,
                                  boolean[] cellCacheEnabled) throws IOException {
            int chunkRows = Math.max(1, properties.getChunkRows());
            int totalChunks = Math.max(1, (data.rows() + chunkRows - 1) / chunkRows);
            ExecutorCompletionService<ChunkTaskResult> completionService =
                    new ExecutorCompletionService<>(chunkExecutor);
            int maxInFlight = properties.getChunkMaxInFlightPerRequest() > 0
                    ? properties.getChunkMaxInFlightPerRequest()
                    : totalChunks;
            maxInFlight = Math.max(1, Math.min(totalChunks, maxInFlight));
            List<ChunkBytes> orderedChunks = new ArrayList<>(totalChunks);
            for (int i = 0; i < totalChunks; i++) {
                orderedChunks.add(null);
            }
            int nextChunkToSubmit = 0;
            int submitted = 0;
            while (submitted < maxInFlight) {
                submitChunk(completionService, data, chunkRows, nextChunkToSubmit,
                        encryptedDictionaries, cellCacheEnabled);
                nextChunkToSubmit++;
                submitted++;
            }
            int nextChunkToWrite = 0;
            try (OutputStream out = throttledOutputStream(output)) {
                for (int completed = 0; completed < totalChunks; completed++) {
                    ChunkTaskResult result = takeChunkResult(completionService);
                    orderedChunks.set(result.chunkIndex, result.chunkBytes);
                    if (nextChunkToSubmit < totalChunks) {
                        submitChunk(completionService, data, chunkRows, nextChunkToSubmit,
                                encryptedDictionaries, cellCacheEnabled);
                        nextChunkToSubmit++;
                    }
                    while (nextChunkToWrite < totalChunks) {
                        ChunkBytes chunk = orderedChunks.get(nextChunkToWrite);
                        if (chunk == null) {
                            break;
                        }
                        orderedChunks.set(nextChunkToWrite, null);
                        out.write(chunk.bytes, 0, chunk.length);
                        chunkBufferPool.release(chunk.bytes);
                        nextChunkToWrite++;
                    }
                }
            }
        }

        private void submitChunk(ExecutorCompletionService<ChunkTaskResult> completionService,
                                 LoadedData data, int chunkRows, int chunkIndex,
                                 RequestEncryptedDictionary[] encryptedDictionaries,
                                 boolean[] cellCacheEnabled) {
            int startRow = chunkIndex * chunkRows;
            int endRow = Math.min(data.rows(), startRow + chunkRows);
            completionService.submit(new RenderChunkTask(
                    data,
                    chunkIndex,
                    startRow,
                    endRow,
                    encryptedDictionaries,
                    cellCacheEnabled));
        }

        private ChunkTaskResult takeChunkResult(ExecutorCompletionService<ChunkTaskResult> completionService)
                throws IOException {
            try {
                Future<ChunkTaskResult> completed = completionService.take();
                return completed.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for chunk", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Failed to render chunk", cause);
            }
        }

        private final class RenderChunkTask implements Callable<ChunkTaskResult> {
            private final LoadedData data;
            private final int chunkIndex;
            private final int startRow;
            private final int endRow;
            private final RequestEncryptedDictionary[] encryptedDictionaries;
            private final boolean[] cellCacheEnabled;

            private RenderChunkTask(LoadedData data, int chunkIndex, int startRow, int endRow,
                                    RequestEncryptedDictionary[] encryptedDictionaries,
                                    boolean[] cellCacheEnabled) {
                this.data = data;
                this.chunkIndex = chunkIndex;
                this.startRow = startRow;
                this.endRow = endRow;
                this.encryptedDictionaries = encryptedDictionaries;
                this.cellCacheEnabled = cellCacheEnabled;
            }

            @Override
            public ChunkTaskResult call() throws IOException {
                Sm4Cipher.Context chunkCipher = chunkCipherContext.get().reset(sm4Key);
                FixedCipherCache[] chunkCaches = new FixedCipherCache[FieldId.FIELD_COUNT];
                int capacity = estimateChunkCapacity(data, startRow, endRow, encryptedDictionaries);
                ChunkCsvWriter writer = new ChunkCsvWriter(chunkBufferPool.borrow(capacity));
                writeRows(data, startRow, endRow, chunkCipher, encryptedDictionaries, chunkCaches, cellCacheEnabled, writer);
                return new ChunkTaskResult(chunkIndex, new ChunkBytes(writer.buffer(), writer.length()));
            }
        }

        private int estimateChunkCapacity(LoadedData data, int startRow, int endRow,
                                          RequestEncryptedDictionary[] encryptedDictionaries) {
            long total = (long) (endRow - startRow) * fieldCount;
            for (int row = startRow; row < endRow; row++) {
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    int fieldId = fields[fieldIndex];
                    ColumnData column = data.column(fieldId);
                    if (FieldId.isMaskField(fieldId)) {
                        total += column.length(row);
                    } else if (encryptedDictionaries[fieldId] != null) {
                        RequestEncryptedDictionary encryptedDictionary = encryptedDictionaries[fieldId];
                        DictionaryColumnData dictionary = data.dictionary(fieldId);
                        int valueId = dictionary.rowValueId(row);
                        total += encryptedDictionary.lengths[valueId];
                    } else {
                        total += encryptedHexLength(column.length(row));
                    }
                }
            }
            if (total > Integer.MAX_VALUE - 1024L) {
                throw new IllegalStateException("Chunk output is too large to buffer: " + total);
            }
            return Math.max(8192, (int) total + 1024);
        }

        private final class ChunkTaskResult {
            private final int chunkIndex;
            private final ChunkBytes chunkBytes;

            private ChunkTaskResult(int chunkIndex, ChunkBytes chunkBytes) {
                this.chunkIndex = chunkIndex;
                this.chunkBytes = chunkBytes;
            }
        }

        private final class ChunkBytes {
            private final byte[] bytes;
            private final int length;

            private ChunkBytes(byte[] bytes, int length) {
                this.bytes = bytes;
                this.length = length;
            }
        }

        private void writeRows(LoadedData data, int startRow, int endRow, Sm4Cipher.Context cipher,
                               RequestEncryptedDictionary[] encryptedDictionaries, FixedCipherCache[] caches,
                               boolean[] cellCacheEnabled, CsvByteWriter writer) throws IOException {
            byte[] cellBuffer = new byte[256];
            byte[] tempBuffer = new byte[256];
            for (int row = startRow; row < endRow; row++) {
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
                        int hexLength = encryptCell(fieldId, cipher, caches, cellCacheEnabled, bytes, offset, length, cellBuffer, 0, tempBuffer);
                        writer.writeBytes(cellBuffer, 0, hexLength);
                    }
                }
                writer.writeByte('\n');
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
                if (shouldUseRequestDictionary(data, fieldId)) {
                    encrypted[fieldId] = encryptDictionary(data.dictionary(fieldId), cipher);
                }
            }
            return encrypted;
        }

        private boolean shouldUseRequestDictionary(LoadedData data, int fieldId) {
            if (!data.hasDictionary(fieldId)) {
                return false;
            }
            DictionaryColumnData dictionary = data.dictionary(fieldId);
            if (dictionary.uniqueCount() >= HIGH_CARDINALITY_DICTIONARY_ROWS
                    && (long) dictionary.uniqueCount() * 100L > (long) data.rows() * MAX_DICTIONARY_UNIQUE_PERCENT) {
                return false;
            }
            return true;
        }

        private boolean isDefinitelyStreamingOutput(LoadedData data) {
            long lowerBound = (long) data.rows() * fieldCount;
            for (int i = 0; i < fieldCount; i++) {
                int fieldId = fields[i];
                if (!FieldId.isMaskField(fieldId)) {
                    lowerBound += (long) data.rows() * MIN_ENCRYPTED_HEX_LENGTH;
                }
            }
            return lowerBound > properties.getMaxBufferedOutputBytes();
        }

        private boolean[] buildCellCachePlan(LoadedData data, RequestEncryptedDictionary[] encryptedDictionaries) {
            boolean[] enabled = new boolean[FieldId.FIELD_COUNT];
            for (int i = 0; i < fieldCount; i++) {
                int fieldId = fields[i];
                enabled[fieldId] = FieldId.shouldCache(fieldId)
                        && encryptedDictionaries[fieldId] == null
                        && shouldUseRequestDictionary(data, fieldId);
            }
            return enabled;
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

        private int encryptCell(int fieldId, Sm4Cipher.Context cipher, FixedCipherCache[] caches, boolean[] cellCacheEnabled, byte[] bytes, int offset, int length, byte[] output, int targetOffset, byte[] temp) {
            if (!cellCacheEnabled[fieldId]) {
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
