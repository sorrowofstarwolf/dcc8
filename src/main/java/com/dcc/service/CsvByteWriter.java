package com.dcc.service;

import java.io.IOException;

interface CsvByteWriter {
    void writeByte(int value) throws IOException;

    void writeBytes(byte[] bytes, int offset, int length) throws IOException;

    void flush() throws IOException;
}
