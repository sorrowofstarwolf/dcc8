package com.dcc.service;

import java.io.IOException;
import java.io.OutputStream;

final class FastCsvWriter implements CsvByteWriter {
    private final OutputStream out;
    private final byte[] buffer;
    private int position;

    FastCsvWriter(OutputStream out, int bufferBytes) {
        this.out = out;
        this.buffer = new byte[Math.max(8192, bufferBytes)];
    }

    @Override
    public void writeByte(int value) throws IOException {
        if (position == buffer.length) {
            flushBuffer();
        }
        buffer[position++] = (byte) value;
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int length) throws IOException {
        if (length >= buffer.length) {
            flushBuffer();
            out.write(bytes, offset, length);
            return;
        }
        if (position + length > buffer.length) {
            flushBuffer();
        }
        System.arraycopy(bytes, offset, buffer, position, length);
        position += length;
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    private void flushBuffer() throws IOException {
        if (position > 0) {
            out.write(buffer, 0, position);
            position = 0;
        }
    }
}
