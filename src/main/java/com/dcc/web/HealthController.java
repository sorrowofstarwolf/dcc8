package com.dcc.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
public class HealthController {
    private static final Map<String, String> SUCCESS = Collections.singletonMap("status", "SUCCESS");

    @GetMapping("/health")
    public Map<String, String> health() {
        // 赛事要求 /health 前不能读取数据、申请加密内存或初始化加密服务，因此这里必须保持纯状态返回。
        return SUCCESS;
    }
}
