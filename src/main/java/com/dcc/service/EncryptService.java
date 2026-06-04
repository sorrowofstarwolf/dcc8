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
import org.springframework.web.client.RestClientException;
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
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EncryptService {
    private static final Logger log = LoggerFactory.getLogger(EncryptService.class);

    private final AppProperties properties;
    private final DataStore dataStore;
    private final Sm4Cipher sm4Cipher;
    private final RestTemplate restTemplate;
    private final ThreadPoolExecutor executor;
    private final GlobalAsyncWriter asyncWriter;
    private final CallbackDispatcher callbackDispatcher;

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
        this.callbackDispatcher = new CallbackDispatcher();
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
        callbackDispatcher.close();
    }

    private void callback(String callbackRequestId, String callbackIp) throws IOException {
        String callbackUrl = properties.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            return;
        }
        try {
            restTemplate.postForObject(
                    callbackUrl,
                    new CallbackRequest(properties.getTeamCode(), callbackRequestId, callbackIp),
                    String.class);
        } catch (RestClientException e) {
            throw new IOException("Failed to callback " + callbackUrl + " for request " + callbackRequestId, e);
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
            RequestState state = asyncWriter.createState(
                    requestId,
                    sm4Key == null ? 0 : sm4Key.length(),
                    fieldCount,
                    data.rows(),
                    ip,
                    output,
                    startNanos,
                    doCallback);
            if (doCallback) {
                callbackDispatcher.beginRequest();
            }
            BufferChunk chunk = takeChunk(state);
            ByteBuffer buffer = chunk.buffer;
            boolean append = false;
            try {
                for (int row = 0; row < data.rows(); row++) {
                    int rowLength = estimateRowLength(data, row);
                    if (rowLength > buffer.capacity()) {
                        throw new IOException("Output row exceeded direct buffer capacity for request " + requestId
                                + ", required more than " + properties.getOutputBufferBytes() + " bytes");
                    }
                    if (buffer.remaining() < rowLength) {
                        asyncWriter.submit(state, chunk, append, false);
                        append = true;
                        chunk = takeChunk(state);
                        buffer = chunk.buffer;
                    }
                    for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                        if (fieldIndex > 0) {
                            buffer.put((byte) ',');
                        }

                        int fieldId = fields[fieldIndex];
                        ColumnData column = data.column(fieldId);
                        byte[] bytes = column.bytes();
                        int offset = column.offset(row);
                        int length = column.length(row);

                        if (FieldId.isMaskField(fieldId)) {
                            buffer.put(bytes, offset, length);
                        } else {
                            int hexLength = encryptCell(fieldId, cipher, caches, bytes, offset, length, cellBuffer);
                            buffer.put(cellBuffer, 0, hexLength);
                        }
                    }
                    buffer.put((byte) '\n');
                }
                return asyncWriter.submit(state, chunk, append, true);
            } catch (IOException e) {
                asyncWriter.recycle(chunk);
                if (state.markCallbackSettled()) {
                    callbackDispatcher.failRequest();
                }
                state.completion.completeExceptionally(e);
                throw e;
            }
        }

        private int estimateRowLength(LoadedData data, int row) {
            int rowLength = 1;
            for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                if (fieldIndex > 0) {
                    rowLength++;
                }
                int fieldId = fields[fieldIndex];
                ColumnData column = data.column(fieldId);
                int length = column.length(row);
                rowLength += FieldId.isMaskField(fieldId) ? length : encryptedHexLength(length);
            }
            return rowLength;
        }

        private int encryptedHexLength(int plainLength) {
            int paddedBytes = ((plainLength >>> 4) + 1) << 4;
            return paddedBytes << 1;
        }

        private BufferChunk takeChunk(RequestState state) throws IOException {
            long waitStart = System.nanoTime();
            BufferChunk chunk = asyncWriter.takeChunk();
            state.takeChunkCount.incrementAndGet();
            state.takeChunkWaitNanos.addAndGet(System.nanoTime() - waitStart);
            return chunk;
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
        private final WriteTask poison = new WriteTask(null, null, false, false);

        private final BlockingQueue<BufferChunk> available;
        private final List<WriterWorker> workers;
        private volatile IOException failure;

        private GlobalAsyncWriter(int bufferBytes, int queueSlots, int writerCount) {
            int slots = Math.max(2, queueSlots);
            int writers = Math.max(1, writerCount);
            this.available = new ArrayBlockingQueue<>(slots);
            for (int i = 0; i < slots; i++) {
                available.add(new BufferChunk(ByteBuffer.allocateDirect(bufferBytes)));
            }
            this.workers = new ArrayList<>(writers);
            for (int i = 0; i < writers; i++) {
                WriterWorker worker = new WriterWorker(this, i, slots);
                worker.thread.start();
                workers.add(worker);
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
            chunk.writtenBytes = 0;
            chunk.buffer.clear();
            available.offer(chunk);
        }

        private RequestState createState(String requestId, int keyLength, int fieldCount, int rows,
                                         String ip, Path output, long startNanos, boolean doCallback) {
            int writerIndex = Math.floorMod(requestId.hashCode(), workers.size());
            return new RequestState(requestId, keyLength, fieldCount, rows, ip, output, startNanos, doCallback, writerIndex);
        }

        private CompletableFuture<Path> submit(RequestState state, BufferChunk chunk, boolean append, boolean lastChunk) throws IOException {
            ensureHealthy();
            try {
                chunk.seal();
                workers.get(state.writerIndex).pending.put(new WriteTask(state, chunk, append, lastChunk));
                return state.completion;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recycle(chunk);
                throw new IOException("Interrupted while queueing a write chunk", e);
            }
        }

        private void runWriterLoop(WriterWorker worker) {
            try {
                while (true) {
                    WriteTask task = worker.pending.take();
                    if (task == poison) {
                        return;
                    }
                    writeTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IOException("Writer thread interrupted", e);
            } catch (IOException e) {
                failure = e;
            } finally {
                failPending(worker);
            }
        }

        private void writeTask(WriteTask task) throws IOException {
            ByteBuffer buffer = task.chunk.readView();
            try {
                long openStartNanos = System.nanoTime();
                task.state.chunkCount.incrementAndGet();
                try (FileChannel channel = FileChannel.open(
                        task.state.output,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        task.append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    task.state.openNanos.addAndGet(System.nanoTime() - openStartNanos);
                    long writeStartNanos = System.nanoTime();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    task.state.writeNanos.addAndGet(System.nanoTime() - writeStartNanos);
                }
                if (task.lastChunk) {
                    long millis = (System.nanoTime() - task.state.startNanos) / 1_000_000L;
                    log.info("requestId={}, fields={}, keyLength={}, rows={}, millis={}, chunks={}, takeChunkCount={}, takeChunkWaitMillis={}, writerOpenMillis={}, writerWriteMillis={}",
                            task.state.requestId,
                            task.state.fieldCount,
                            task.state.keyLength,
                            task.state.rows,
                            millis,
                            task.state.chunkCount.get(),
                            task.state.takeChunkCount.get(),
                            TimeUnit.NANOSECONDS.toMillis(task.state.takeChunkWaitNanos.get()),
                            TimeUnit.NANOSECONDS.toMillis(task.state.openNanos.get()),
                            TimeUnit.NANOSECONDS.toMillis(task.state.writeNanos.get()));
                    if (task.state.doCallback && task.state.markCallbackSettled()) {
                        callbackDispatcher.submit(task.state.requestId, task.state.ip);
                    }
                    task.state.completion.complete(task.state.output);
                }
            } catch (IOException e) {
                if (task.state != null && task.state.markCallbackSettled()) {
                    callbackDispatcher.failRequest();
                }
                task.state.completion.completeExceptionally(e);
                throw e;
            } finally {
                recycle(task.chunk);
            }
        }

        private void failPending(WriterWorker worker) {
            WriteTask task;
            while ((task = worker.pending.poll()) != null) {
                if (task.chunk != null) {
                    recycle(task.chunk);
                }
                if (task.state != null) {
                    if (task.state.markCallbackSettled()) {
                        callbackDispatcher.failRequest();
                    }
                    task.state.completion.completeExceptionally(failure != null ? failure : new IOException("Writer stopped"));
                }
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
                for (WriterWorker worker : workers) {
                    worker.pending.put(poison);
                }
                for (WriterWorker worker : workers) {
                    worker.thread.join();
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
        private int writtenBytes;

        private BufferChunk(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        private void seal() {
            writtenBytes = buffer.position();
        }

        private ByteBuffer readView() {
            ByteBuffer view = buffer.duplicate();
            view.position(0);
            view.limit(writtenBytes);
            return view;
        }
    }

    private final class CallbackDispatcher implements Closeable {
        private final Queue<CallbackTask> pending = new ConcurrentLinkedQueue<>();
        private final ExecutorService executor;
        private final AtomicInteger activeRequests = new AtomicInteger();

        private CallbackDispatcher() {
            int threads = Math.max(1, properties.getAsyncWriteWorkerThreads());
            this.executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "dcc-callback-dispatcher");
                thread.setDaemon(true);
                return thread;
            });
        }

        private void beginRequest() {
            if (!isEnabled()) {
                return;
            }
            activeRequests.incrementAndGet();
        }

        private void submit(String requestId, String ip) {
            if (!isEnabled()) {
                return;
            }
            pending.offer(new CallbackTask(requestId, ip));
            flushIfIdle(activeRequests.decrementAndGet());
        }

        private void failRequest() {
            if (!isEnabled()) {
                return;
            }
            flushIfIdle(activeRequests.decrementAndGet());
        }

        private boolean isEnabled() {
            String callbackUrl = properties.getCallbackUrl();
            return callbackUrl != null && !callbackUrl.isEmpty();
        }

        private void flushIfIdle(int remaining) {
            if (remaining != 0) {
                return;
            }
            CallbackTask task;
            while ((task = pending.poll()) != null) {
                CallbackTask callbackTask = task;
                executor.execute(() -> {
                    try {
                        callback(callbackTask.requestId, callbackTask.ip);
                    } catch (IOException e) {
                        log.error("Callback failed for requestId={}", callbackTask.requestId, e);
                    }
                });
            }
        }

        @Override
        public void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(properties.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing callback dispatcher", e);
            }
        }
    }

    private final class WriterWorker {
        private final BlockingQueue<WriteTask> pending;
        private final Thread thread;
        private final GlobalAsyncWriter owner;

        private WriterWorker(GlobalAsyncWriter owner, int index, int queueSlots) {
            this.owner = owner;
            this.pending = new ArrayBlockingQueue<>(queueSlots);
            this.thread = new Thread(() -> owner.runWriterLoop(this), "dcc-file-writer-" + index);
            this.thread.setDaemon(true);
        }
    }

    private static final class RequestState {
        private final String requestId;
        private final int keyLength;
        private final int fieldCount;
        private final int rows;
        private final String ip;
        private final Path output;
        private final long startNanos;
        private final boolean doCallback;
        private final int writerIndex;
        private final CompletableFuture<Path> completion = new CompletableFuture<>();
        private final AtomicInteger chunkCount = new AtomicInteger();
        private final AtomicInteger takeChunkCount = new AtomicInteger();
        private final AtomicLong takeChunkWaitNanos = new AtomicLong();
        private final AtomicLong openNanos = new AtomicLong();
        private final AtomicLong writeNanos = new AtomicLong();
        private final AtomicBoolean callbackSettled = new AtomicBoolean();

        private RequestState(String requestId, int keyLength, int fieldCount, int rows,
                             String ip, Path output, long startNanos,
                             boolean doCallback, int writerIndex) {
            this.requestId = requestId;
            this.keyLength = keyLength;
            this.fieldCount = fieldCount;
            this.rows = rows;
            this.ip = ip;
            this.output = output;
            this.startNanos = startNanos;
            this.doCallback = doCallback;
            this.writerIndex = writerIndex;
        }

        private boolean markCallbackSettled() {
            return doCallback && callbackSettled.compareAndSet(false, true);
        }
    }

    private static final class WriteTask {
        private final RequestState state;
        private final BufferChunk chunk;
        private final boolean append;
        private final boolean lastChunk;

        private WriteTask(RequestState state, BufferChunk chunk, boolean append, boolean lastChunk) {
            this.state = state;
            this.chunk = chunk;
            this.append = append;
            this.lastChunk = lastChunk;
        }
    }

    private static final class CallbackTask {
        private final String requestId;
        private final String ip;

        private CallbackTask(String requestId, String ip) {
            this.requestId = requestId;
            this.ip = ip;
        }
    }
}
