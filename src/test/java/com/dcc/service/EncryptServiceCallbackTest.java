package com.dcc.service;

import com.dcc.config.AppProperties;
import com.dcc.crypto.Sm4Cipher;
import com.dcc.model.EncryptRequest;
import com.dcc.store.DataStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptServiceCallbackTest {
    @TempDir
    Path tempDir;

    @Test
    void postsCallbackAfterOutputFileIsWritten() throws Exception {
        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<String> callbackBody = new AtomicReference<>();
        AtomicBoolean fileExistedAtCallback = new AtomicBoolean(false);
        String requestId = "REQ_CALLBACK_0";
        Path outputFile = tempDir.resolve(requestId + ".csv");

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/callback", exchange -> handleCallback(exchange, outputFile, callbackBody, fileExistedAtCallback, callbackLatch));
        server.start();

        AppProperties properties = new AppProperties();
        properties.setDatasetPath("table_data_baseline.csv");
        properties.setOutputDir(tempDir.toString());
        properties.setExpectedRows(16);
        properties.setInitialRawPoolBytes(64 * 1024);
        properties.setInitialMaskPoolBytes(64 * 1024);
        properties.setCacheCapacity(64);
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(4);
        properties.setAsyncWriteWorkerThreads(1);
        properties.setAsyncWriteQueueSlots(2);
        properties.setOutputBufferBytes(8 * 1024);
        properties.setCallbackUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/callback");

        EncryptService service = new EncryptService(properties, new DataStore(properties, new MaskingService()), new Sm4Cipher());
        try {
            EncryptRequest request = new EncryptRequest();
            request.setRequestId(requestId);
            request.setSm4Key("2123433411630000");
            request.setIp("55.51.53.74");
            request.setFieldsToEncrypt(new String[]{"phone", "user_code", "user_id", "name"});

            service.submit(request);

            assertThat(callbackLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.exists(outputFile)).isTrue();
            assertThat(fileExistedAtCallback.get()).isTrue();
            assertThat(callbackBody.get()).contains("\"teamCode\":\"team16\"");
            assertThat(callbackBody.get()).contains("\"requestId\":\"" + requestId + "\"");
            assertThat(callbackBody.get()).contains("\"ip\":\"55.51.53.74\"");
        } finally {
            service.shutdown();
            server.stop(0);
        }
    }

    private static void handleCallback(HttpExchange exchange, Path outputFile,
                                       AtomicReference<String> callbackBody,
                                       AtomicBoolean fileExistedAtCallback,
                                       CountDownLatch callbackLatch) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            callbackBody.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        fileExistedAtCallback.set(Files.exists(outputFile));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        callbackLatch.countDown();
    }
}
