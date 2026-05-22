package com.dcc.crypto;

public final class Hashing {
    private Hashing() {
    }

    public static int hash(byte[] bytes, int offset, int length) {
        int h = 0x811C9DC5;
        for (int i = 0; i < length; i++) {
            h ^= bytes[offset + i] & 0xFF;
            h *= 0x01000193;
        }
        return h == 0 ? 1 : h;
    }
}
