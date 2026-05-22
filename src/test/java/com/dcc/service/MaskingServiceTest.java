package com.dcc.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingServiceTest {
    private final MaskingService maskingService = new MaskingService();

    @Test
    void masksShortValuesWithFiveHashes() {
        assertThat(mask("1823")).isEqualTo("1#####");
        assertThat(mask("何军")).isEqualTo("何#####");
    }

    @Test
    void masksLongValuesWithFourHashesAndTail() {
        assertThat(mask("zhangsan@qq.com")).isEqualTo("z####m");
    }

    private String mask(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new String(maskingService.mask(bytes, 0, bytes.length), StandardCharsets.UTF_8);
    }
}
