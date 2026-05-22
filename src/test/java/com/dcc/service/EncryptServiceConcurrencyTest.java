package com.dcc.service;

import com.dcc.config.AppProperties;
import com.dcc.crypto.Sm4Cipher;
import com.dcc.model.EncryptRequest;
import com.dcc.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptServiceConcurrencyTest {
    @TempDir
    Path tempDir;

    @Test
    void handlesConcurrentRequestsAgainstSingleLoadedDataset() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setDatasetPath("table_data_baseline.csv");
        properties.setOutputDir(tempDir.toString());
        properties.setExpectedRows(16);
        properties.setInitialRawPoolBytes(64 * 1024);
        properties.setInitialMaskPoolBytes(64 * 1024);
        properties.setCacheCapacity(64);
        properties.setWorkerThreads(3);
        properties.setQueueCapacity(32);

        EncryptService service = new EncryptService(properties, new DataStore(properties, new MaskingService()), new Sm4Cipher());
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            final int id = i;
            callers.execute(() -> {
                EncryptRequest request = new EncryptRequest();
                request.setRequestId("REQ_CONC_" + id);
                request.setSm4Key("2123433411630000");
                request.setIp("55.51.53.74");
                request.setFieldsToEncrypt(new String[]{"phone", "user_code", "user_id", "name"});
                service.generateBlocking(request);
                latch.countDown();
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        callers.shutdownNow();
        assertThat(Files.list(tempDir).count()).isEqualTo(20);
        service.shutdown();
    }
}
