package com.dcc.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Sm4CipherTest {
    @Test
    void encryptsToUppercaseHex() {
        Sm4Cipher.Context context = new Sm4Cipher().newContext("2123433411630000");
        byte[] source = "CZJE".getBytes(StandardCharsets.UTF_8);
        byte[] target = new byte[64];

        int length = context.encryptToHex(source, 0, source.length, target, 0);

        assertThat(new String(target, 0, length, StandardCharsets.US_ASCII))
                .isEqualTo("91DFB6ABB4860D131618809DB26B7A5E");
    }
}
