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
import com.dcc.store.LoadedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private final GlobalAsyncWriter asyncWriter;

    public EncryptService(AppProperties properties, DataStore dataStore, Sm4Cipher sm4Cipher) {
        this.properties = properties;
        this.dataStore = dataStore;
        this.sm4Cipher = sm4Cipher;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRequestTimeoutMillis());
        requestFactory.setReadTimeout(properties.getRequestTimeoutMillis());
        this.restTemplate = new RestTemplate(requestFactory);

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
        this.asyncWriter = new GlobalAsyncWriter(
                properties.getOutputBufferBytes(),
                properties.getAsyncWriteQueueSlots(),
                properties.getAsyncWriteWorkerThreads());
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
        for (int i = 0; i < names.length; i++) {
            fields[i] = FieldId.fromName(names[i]);
        }
        return new EncryptTask(request.getRequestId(), request.getSm4Key(), request.getIp(), fields, names.length).call(false);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        asyncWriter.close();
    }

    private void callback(String callbackRequestId, String callbackIp) {
        String callbackUrl = properties.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        restTemplate.postForObject(
                callbackUrl,
                new CallbackRequest(properties.getTeamCode(), callbackRequestId, callbackIp),
                String.class);
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
                CompletableFuture<Path> completion = writeFile(data, output, start, doCallback);
                if (doCallback) {
                    return output;
                }
                return awaitCompletion(completion);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write output file for " + requestId, e);
            }
        }

        private CompletableFuture<Path> writeFile(LoadedData data, Path output, long startNanos, boolean doCallback) throws IOException {
            Sm4Cipher.Context cipher = sm4Cipher.newContext(sm4Key);
            FixedCipherCache[] caches = new FixedCipherCache[FieldId.FIELD_COUNT];
            byte[] cellBuffer = new byte[256];
            byte[] rowBuffer = new byte[2048];
            BufferChunk chunk = asyncWriter.takeChunk();
            ByteBuffer buffer = chunk.buffer;
            buffer.clear();
            try {
                for (int row = 0; row < data.rows(); row++) {
                    int rowLength = 0;
                    for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                        if (fieldIndex > 0) {
                            rowBuffer = ensureCapacity(rowBuffer, rowLength + 1);
                            rowBuffer[rowLength++] = (byte) ',';
                        }

                        int fieldId = fields[fieldIndex];
                        ColumnData column = data.column(fieldId);
                        byte[] bytes = column.bytes();
                        int offset = column.offset(row);
                        int length = column.length(row);

                        if (FieldId.isMaskField(fieldId)) {
                            rowBuffer = ensureCapacity(rowBuffer, rowLength + length + 1);
                            System.arraycopy(bytes, offset, rowBuffer, rowLength, length);
                            rowLength += length;
                        } else {
                            int hexLength = encryptCell(fieldId, cipher, caches, bytes, offset, length, cellBuffer);
                            rowBuffer = ensureCapacity(rowBuffer, rowLength + hexLength + 1);
                            System.arraycopy(cellBuffer, 0, rowBuffer, rowLength, hexLength);
                            rowLength += hexLength;
                        }
                    }

                    rowBuffer = ensureCapacity(rowBuffer, rowLength + 1);
                    rowBuffer[rowLength++] = (byte) '\n';
                    if (buffer.remaining() < rowLength) {
                        throw new IOException("Output exceeded direct buffer capacity for request " + requestId
                                + ", required more than " + properties.getOutputBufferBytes() + " bytes");
                    }
                    buffer.put(rowBuffer, 0, rowLength);
                }
                return asyncWriter.submit(new CompletedRequest(
                        requestId,
                        sm4Key == null ? 0 : sm4Key.length(),
                        fieldCount,
                        data.rows(),
                        ip,
                        output,
                        chunk,
                        startNanos,
                        doCallback));
            } catch (IOException e) {
                chunk.buffer.clear();
                asyncWriter.recycle(chunk);
                throw e;
            }
        }

        private Path awaitCompletion(CompletableFuture<Path> completion) throws IOException {
            try {
                return completion.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for file write completion", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Failed while waiting for file write completion", cause);
            }
        }

        private byte[] ensureCapacity(byte[] buffer, int required) {
            if (required <= buffer.length) {
                return buffer;
            }
            int next = buffer.length;
            while (next < required) {
                next <<= 1;
            }
            byte[] expanded = new byte[next];
            System.arraycopy(buffer, 0, expanded, 0, buffer.length);
            return expanded;
        }

        private int encryptCell(int fieldId, Sm4Cipher.Context cipher, FixedCipherCache[] caches,
                                byte[] bytes, int offset, int length, byte[] output) {
            if (!FieldId.shouldCache(fieldId)) {
                return cipher.encryptToHex(bytes, offset, length, output, 0);
            }

            FixedCipherCache cache = caches[fieldId];
            if (cache == null) {
                int valueBytes = Math.max(1024 * 1024, properties.getCacheCapacity() * 64);
                cache = new FixedCipherCache(properties.getCacheCapacity(), valueBytes);
                caches[fieldId] = cache;
            }

            int hash = Hashing.hash(bytes, offset, length);
            int cached = cache.get(bytes, offset, length, hash, output, 0);
            if (cached >= 0) {
                return cached;
            }

            int hexLength = cipher.encryptToHex(bytes, offset, length, output, 0);
            cache.put(bytes, offset, length, hash, output, 0, hexLength);
            return hexLength;
        }

    }

    private final class GlobalAsyncWriter implements Closeable {
        private final CompletedRequest poison = new CompletedRequest(null, 0, 0, 0, null, null, null, 0L, false);

        private final BlockingQueue<BufferChunk> available;
        private final BlockingQueue<CompletedRequest> pending;
        private final List<Thread> writerThreads;
        private volatile IOException failure;

        private GlobalAsyncWriter(int bufferBytes, int queueSlots, int writerCount) {
            int slots = Math.max(2, queueSlots);
            int writers = Math.max(1, writerCount);
            this.available = new ArrayBlockingQueue<>(slots);
            this.pending = new ArrayBlockingQueue<>(slots);
            for (int i = 0; i < slots; i++) {
                available.add(new BufferChunk(ByteBuffer.allocateDirect(bufferBytes)));
            }
            this.writerThreads = new ArrayList<>(writers);
            for (int i = 0; i < writers; i++) {
                Thread writerThread = new Thread(this::runWriterLoop, "dcc-file-writer-" + i);
                writerThread.setDaemon(true);
                writerThread.start();
                writerThreads.add(writerThread);
            }
        }

        private BufferChunk takeChunk() throws IOException {
            ensureHealthy();
            try {
                return available.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for a direct write buffer", e);
            }
        }

        private void recycle(BufferChunk chunk) {
            if (chunk == null) {
                return;
            }
            chunk.buffer.clear();
            available.offer(chunk);
        }

        private CompletableFuture<Path> submit(CompletedRequest request) throws IOException {
            ensureHealthy();
            try {
                pending.put(request);
                return request.completion;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recycle(request.chunk);
                throw new IOException("Interrupted while queueing a completed request", e);
            }
        }

        private void runWriterLoop() {
            try {
                while (true) {
                    CompletedRequest request = pending.take();
                    if (request == poison) {
                        return;
                    }
                    writeRequest(request);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IOException("Writer thread interrupted", e);
            } catch (IOException e) {
                failure = e;
            } finally {
                failPending();
            }
        }

        private void writeRequest(CompletedRequest request) throws IOException {
            ByteBuffer buffer = request.chunk.buffer;
            try {
                try (FileChannel channel = FileChannel.open(
                        request.output,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                long millis = (System.nanoTime() - request.startNanos) / 1_000_000L;
                log.info("requestId={}, fields={}, keyLength={}, rows={}, millis={}",
                        request.requestId, request.fieldCount, request.keyLength, request.rows, millis);
                if (request.doCallback) {
                    callback(request.requestId, request.ip);
                }
                request.completion.complete(request.output);
            } finally {
                recycle(request.chunk);
            }
        }

        private void failPending() {
            CompletedRequest request;
            while ((request = pending.poll()) != null) {
                if (request.chunk != null) {
                    recycle(request.chunk);
                }
                request.completion.completeExceptionally(failure != null ? failure : new IOException("Writer stopped"));
            }
        }

        private void ensureHealthy() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void close() {
            IOException closeFailure = null;
            try {
                for (int i = 0; i < writerThreads.size(); i++) {
                    pending.put(poison);
                }
                for (Thread writerThread : writerThreads) {
                    writerThread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeFailure = new IOException("Interrupted while closing global async writer", e);
            }
            if (failure != null && closeFailure == null) {
                closeFailure = failure;
            }
            if (closeFailure != null) {
                throw new IllegalStateException("Failed to close async writer", closeFailure);
            }
        }
    }

    private static final class BufferChunk {
        private final ByteBuffer buffer;

        private BufferChunk(ByteBuffer buffer) {
            this.buffer = buffer;
        }
    }

    private static final class CompletedRequest {
        private final String requestId;
        private final int keyLength;
        private final int fieldCount;
        private final int rows;
        private final String ip;
        private final Path output;
        private final BufferChunk chunk;
        private final long startNanos;
        private final boolean doCallback;
        private final CompletableFuture<Path> completion = new CompletableFuture<>();

        private CompletedRequest(String requestId, int keyLength, int fieldCount, int rows,
                                 String ip, Path output, BufferChunk chunk, long startNanos,
                                 boolean doCallback) {
            this.requestId = requestId;
            this.keyLength = keyLength;
            this.fieldCount = fieldCount;
            this.rows = rows;
            this.ip = ip;
            this.output = output;
            this.chunk = chunk;
            this.startNanos = startNanos;
            this.doCallback = doCallback;
        }
    }
}
