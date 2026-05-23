package com.dcc.store;

import com.dcc.crypto.Hashing;

public final class DictionaryColumnData {
    private final byte[] bytes;
    private final int[] rowValueIds;
    private final int[] uniqueOffsets;
    private final int[] uniqueLengths;
    private final int[] hashes;
    private int uniqueCount;

    DictionaryColumnData(byte[] bytes, int rows) {
        this.bytes = bytes;
        this.rowValueIds = new int[rows];
        this.uniqueOffsets = new int[rows];
        this.uniqueLengths = new int[rows];
        this.hashes = new int[tableCapacity(rows)];
    }

    void addRowValue(int row, byte[] source, int sourceOffset, int length, int poolOffset) {
        int hash = Hashing.hash(source, sourceOffset, length);
        int id = findOrAdd(source, sourceOffset, length, poolOffset, hash);
        rowValueIds[row] = id;
    }

    public byte[] bytes() {
        return bytes;
    }

    public int rowValueId(int row) {
        return rowValueIds[row];
    }

    public int uniqueOffset(int valueId) {
        return uniqueOffsets[valueId];
    }

    public int uniqueLength(int valueId) {
        return uniqueLengths[valueId];
    }

    public int uniqueCount() {
        return uniqueCount;
    }

    private int findOrAdd(byte[] source, int sourceOffset, int length, int poolOffset, int hash) {
        int mask = hashes.length - 1;
        int slot = hash & mask;
        for (int probes = 0; probes < hashes.length; probes++) {
            int entry = hashes[slot];
            if (entry == 0) {
                int id = uniqueCount++;
                uniqueOffsets[id] = poolOffset;
                uniqueLengths[id] = length;
                hashes[slot] = id + 1;
                return id;
            }
            int id = entry - 1;
            if (uniqueLengths[id] == length && equals(source, sourceOffset, uniqueOffsets[id], length)) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        throw new IllegalStateException("Dictionary hash table is full");
    }

    private boolean equals(byte[] source, int sourceOffset, int poolOffset, int length) {
        for (int i = 0; i < length; i++) {
            if (source[sourceOffset + i] != bytes[poolOffset + i]) {
                return false;
            }
        }
        return true;
    }

    private static int tableCapacity(int rows) {
        int capacity = 1;
        int target = Math.max(2, rows * 2);
        while (capacity < target) {
            capacity <<= 1;
        }
        return capacity;
    }
}
