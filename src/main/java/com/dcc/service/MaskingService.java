package com.dcc.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MaskingService {
    private static final String FIVE = "#####";
    private static final String FOUR = "####";

    public byte[] mask(byte[] source, int offset, int length) {
        String value = new String(source, offset, length, StandardCharsets.UTF_8);
        if (value.isEmpty()) {
            return new byte[0];
        }
        if (value.length() <= 6) {
            return (value.charAt(0) + FIVE).getBytes(StandardCharsets.UTF_8);
        }
        return (value.charAt(0) + FOUR + value.charAt(value.length() - 1)).getBytes(StandardCharsets.UTF_8);
    }
}
