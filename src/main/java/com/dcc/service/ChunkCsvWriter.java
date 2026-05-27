package com.dcc.service;

import java.util.Arrays;

final class ChunkCsvWriter implements CsvByteWriter {
    private byte[] buffer;
    private int position;

    ChunkCsvWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(8192, initialCapacity)];
    }

    ChunkCsvWriter(byte[] buffer) {
        this.buffer = buffer;
    }

    @Override
    public void writeByte(int value) {
        ensureCapacity(1);
        buffer[position++] = (byte) value;
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int length) {
        ensureCapacity(length);
        System.arraycopy(bytes, offset, buffer, position, length);
        position += length;
    }

    @Override
    public void flush() {
    }

    byte[] buffer() {
        return buffer;
    }

    int length() {
        return position;
    }

    private void ensureCapacity(int additionalBytes) {
        int required = position + additionalBytes;
        if (required <= buffer.length) {
            return;
        }
        int newLength = buffer.length;
        while (newLength < required) {
            newLength = newLength + (newLength >> 1);
        }
        buffer = Arrays.copyOf(buffer, newLength);
    }
}
