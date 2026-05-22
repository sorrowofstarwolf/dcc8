package com.dcc.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MaskingService {
    private static final String FIVE = "#####";
    private static final String FOUR = "####";

    public byte[] mask(byte[] source, int offset, int length) {
        // 掩码规则按“字符”计算，不能按 UTF-8 字节截断，否则中文姓名会被切坏。
        // 该方法只在加载阶段调用，不在每个请求热路径中调用，因此这里允许短暂创建 String 保证正确性。
        String value = new String(source, offset, length, StandardCharsets.UTF_8);
        int chars = value.codePointCount(0, value.length());
        if (chars <= 0) {
            return FIVE.getBytes(StandardCharsets.UTF_8);
        }
        int firstEnd = value.offsetByCodePoints(0, 1);
        if (chars <= 6) {
            // 长度小于等于 6：首字符 + 5 个 #。
            return (value.substring(0, firstEnd) + FIVE).getBytes(StandardCharsets.UTF_8);
        }
        int lastStart = value.offsetByCodePoints(0, chars - 1);
        // 长度大于 6：首字符 + 4 个 # + 尾字符。
        return (value.substring(0, firstEnd) + FOUR + value.substring(lastStart)).getBytes(StandardCharsets.UTF_8);
    }
}
