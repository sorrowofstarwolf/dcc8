package com.dcc.service;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class ChunkBufferPool {
    private final int maxBytes;
    private final ConcurrentLinkedQueue<byte[]> buffers = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooledBytes = new AtomicInteger();

    ChunkBufferPool(int maxBytes) {
        this.maxBytes = Math.max(0, maxBytes);
    }

    byte[] borrow(int minCapacity) {
        if (maxBytes == 0) {
            return new byte[minCapacity];
        }
        byte[] buffer;
        while ((buffer = buffers.poll()) != null) {
            pooledBytes.addAndGet(-buffer.length);
            if (buffer.length >= minCapacity) {
                return buffer;
            }
        }
        return new byte[minCapacity];
    }

    void release(byte[] buffer) {
        if (maxBytes == 0 || buffer.length > maxBytes) {
            return;
        }
        while (true) {
            int current = pooledBytes.get();
            int next = current + buffer.length;
            if (next > maxBytes) {
                return;
            }
            if (pooledBytes.compareAndSet(current, next)) {
                buffers.offer(buffer);
                return;
            }
        }
    }
}
