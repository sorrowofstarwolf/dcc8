package com.dcc.crypto;

public final class FixedCipherCache {
    private final int mask;
    // 使用开放寻址哈希表，容量构造时固定，避免 HashMap 节点对象和 rehash 扩容。
    private final int[] hashes;
    private final int[] keyOffsets;
    private final int[] keyLengths;
    private final int[] valueOffsets;
    private final int[] valueLengths;
    private final byte[] values;
    private int valuePosition;

    public FixedCipherCache(int configuredCapacity, int valueBytes) {
        int capacity = 1;
        while (capacity < configuredCapacity) {
            capacity <<= 1;
        }
        this.mask = capacity - 1;
        this.hashes = new int[capacity];
        this.keyOffsets = new int[capacity];
        this.keyLengths = new int[capacity];
        this.valueOffsets = new int[capacity];
        this.valueLengths = new int[capacity];
        this.values = new byte[valueBytes];
    }

    public int get(byte[] keyBytes, int keyOffset, int keyLength, int hash, byte[] target, int targetOffset) {
        int slot = hash & mask;
        while (hashes[slot] != 0) {
            // hash 命中后仍比较长度和原文字节，避免哈希碰撞导致密文错误。
            if (hashes[slot] == hash && keyLengths[slot] == keyLength && equals(keyBytes, keyOffset, keyOffsets[slot], keyLength)) {
                int valueOffset = valueOffsets[slot];
                int valueLength = valueLengths[slot];
                System.arraycopy(values, valueOffset, target, targetOffset, valueLength);
                return valueLength;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    public void put(byte[] keyBytes, int keyOffset, int keyLength, int hash, byte[] value, int valueOffset, int valueLength) {
        if (valuePosition + valueLength > values.length) {
            // 缓存空间耗尽时直接放弃本次缓存，保持正确性优先，避免运行中扩容复制大数组。
            return;
        }
        int slot = hash & mask;
        while (hashes[slot] != 0) {
            if (hashes[slot] == hash && keyLengths[slot] == keyLength && equals(keyBytes, keyOffset, keyOffsets[slot], keyLength)) {
                return;
            }
            slot = (slot + 1) & mask;
        }
        hashes[slot] = hash == 0 ? 1 : hash;
        // keyOffset 指向全局列式字节池，缓存只记录偏移和长度，不复制原文字段。
        keyOffsets[slot] = keyOffset;
        keyLengths[slot] = keyLength;
        valueOffsets[slot] = valuePosition;
        valueLengths[slot] = valueLength;
        System.arraycopy(value, valueOffset, values, valuePosition, valueLength);
        valuePosition += valueLength;
    }

    private boolean equals(byte[] bytes, int leftOffset, int rightOffset, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[leftOffset + i] != bytes[rightOffset + i]) {
                return false;
            }
        }
        return true;
    }
}
