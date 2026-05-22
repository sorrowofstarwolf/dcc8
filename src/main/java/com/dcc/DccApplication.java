package com.dcc;

import com.dcc.config.AppProperties;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.security.Security;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DccApplication implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DccApplication.class);
    private final AppProperties properties;

    public DccApplication(AppProperties properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        SpringApplication.run(DccApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动完成后只打印自定义配置项，方便在赛事机器日志中确认实际生效的路径、线程数和预分配容量。
        // 注意这里不打印任何请求密钥，避免把 sm4Key 泄露到日志里。
        log.info("dcc.teamCode={}", properties.getTeamCode());
        log.info("dcc.datasetPath={}", properties.getDatasetPath());
        log.info("dcc.outputDir={}", properties.getOutputDir());
        log.info("dcc.callbackUrl={}", properties.getCallbackUrl());
        log.info("dcc.workerThreads={}", properties.getWorkerThreads());
        log.info("dcc.queueCapacity={}", properties.getQueueCapacity());
        log.info("dcc.expectedRows={}", properties.getExpectedRows());
        log.info("dcc.initialRawPoolBytes={}", properties.getInitialRawPoolBytes());
        log.info("dcc.initialMaskPoolBytes={}", properties.getInitialMaskPoolBytes());
        log.info("dcc.outputBufferBytes={}", properties.getOutputBufferBytes());
        log.info("dcc.cacheCapacity={}", properties.getCacheCapacity());
        log.info("dcc.requestTimeoutMillis={}", properties.getRequestTimeoutMillis());
        log.info("dcc.baselineValidationEnabled={}", properties.isBaselineValidationEnabled());
    }
}
