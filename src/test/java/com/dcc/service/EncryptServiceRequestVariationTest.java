package com.dcc.service;

import com.dcc.config.AppProperties;
import com.dcc.crypto.Sm4Cipher;
import com.dcc.model.EncryptRequest;
import com.dcc.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptServiceRequestVariationTest {
    @TempDir
    Path tempDir;

    @Test
    void differentKeysProduceDifferentOutputs() throws Exception {
        EncryptService service = newService();
        try {
            Path outputA = service.generateBlocking(request(
                    "REQ_KEY_A",
                    "2123433411630000",
                    new String[]{"user_id", "user_code", "serial_no", "name"}));
            Path outputB = service.generateBlocking(request(
                    "REQ_KEY_B",
                    "3123433411630000",
                    new String[]{"user_id", "user_code", "serial_no", "name"}));

            assertThat(Files.readAllBytes(outputA)).isNotEqualTo(Files.readAllBytes(outputB));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void differentFieldSetsProduceDifferentOutputs() throws Exception {
        EncryptService service = newService();
        try {
            Path outputA = service.generateBlocking(request(
                    "REQ_FIELD_A",
                    "2123433411630000",
                    new String[]{"user_id", "user_code", "serial_no", "name"}));
            Path outputB = service.generateBlocking(request(
                    "REQ_FIELD_B",
                    "2123433411630000",
                    new String[]{"user_id", "name"}));

            assertThat(Files.readAllBytes(outputA)).isNotEqualTo(Files.readAllBytes(outputB));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void acceptsAllDatasetFieldsWhenRequested() throws Exception {
        EncryptService service = newService();
        try {
            Path output = service.generateBlocking(request(
                    "REQ_ALL_FIELDS",
                    "2123433411630000",
                    new String[]{
                            "user_id",
                            "serial_no",
                            "user_code",
                            "business_key",
                            "id_card",
                            "phone",
                            "name",
                            "email",
                            "device_id",
                            "trans_id",
                            "secret_code"
                    }));

            String firstLine = Files.readAllLines(output).get(0);
            assertThat(firstLine.split(",", -1)).hasSize(11);
        } finally {
            service.shutdown();
        }
    }

    private EncryptService newService() {
        AppProperties properties = new AppProperties();
        properties.setDatasetPath("table_data_baseline.csv");
        properties.setOutputDir(tempDir.toString());
        properties.setExpectedRows(16);
        properties.setInitialRawPoolBytes(64 * 1024);
        properties.setInitialMaskPoolBytes(64 * 1024);
        properties.setCacheCapacity(64);
        properties.setCallbackUrl("");
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(4);
        properties.setAsyncWriteWorkerThreads(1);
        properties.setAsyncWriteQueueSlots(2);
        properties.setOutputBufferBytes(8 * 1024);
        return new EncryptService(properties, new DataStore(properties, new MaskingService()), new Sm4Cipher());
    }

    private static EncryptRequest request(String requestId, String sm4Key, String[] fields) {
        EncryptRequest request = new EncryptRequest();
        request.setRequestId(requestId);
        request.setSm4Key(sm4Key);
        request.setIp("55.51.53.74");
        request.setFieldsToEncrypt(fields);
        return request;
    }
}
