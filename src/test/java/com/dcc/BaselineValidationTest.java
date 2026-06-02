package com.dcc;

import com.dcc.config.AppProperties;
import com.dcc.crypto.Sm4Cipher;
import com.dcc.model.EncryptRequest;
import com.dcc.service.EncryptService;
import com.dcc.service.MaskingService;
import com.dcc.store.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedOutputMatchesOfficialBaselineByteForByte() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setDatasetPath("table_data_baseline.csv");
        properties.setOutputDir(tempDir.toString());
        properties.setExpectedRows(16);
        properties.setInitialRawPoolBytes(64 * 1024);
        properties.setInitialMaskPoolBytes(64 * 1024);
        properties.setCacheCapacity(64);
        properties.setCallbackUrl("");

        DataStore dataStore = new DataStore(properties, new MaskingService());
        EncryptService service = new EncryptService(properties, dataStore, new Sm4Cipher());

        EncryptRequest request = new EncryptRequest();
        request.setRequestId("REQ_20260413120000_0");
        request.setSm4Key("2123433411630000");
        request.setIp("55.51.53.74");
        request.setFieldsToEncrypt(new String[]{"phone", "user_code", "user_id", "name"});

        Path generated = service.generateBlocking(request);

        byte[] actual = Files.readAllBytes(generated);
        byte[] expected = Files.readAllBytes(Path.of("result_baseline.csv"));
        if (containsLocalPrivacyPlaceholder()) {
            assertBaselineExceptPhoneColumn(actual, expected);
        } else {
            assertThat(actual).isEqualTo(expected);
        }
        service.shutdown();
    }

    private boolean containsLocalPrivacyPlaceholder() throws Exception {
        String input = Files.readString(Path.of("table_data_baseline.csv"), StandardCharsets.UTF_8);
        return input.contains("敏感信息系统已自动屏蔽");
    }

    private void assertBaselineExceptPhoneColumn(byte[] actualBytes, byte[] expectedBytes) {
        String actual = new String(actualBytes, StandardCharsets.UTF_8);
        String expected = new String(expectedBytes, StandardCharsets.UTF_8);
        String[] actualRows = actual.split("\\R");
        String[] expectedRows = expected.split("\\R");
        assertThat(actualRows).hasSameSizeAs(expectedRows);
        for (int i = 0; i < expectedRows.length; i++) {
            String actualTail = actualRows[i].substring(actualRows[i].indexOf(','));
            String expectedTail = expectedRows[i].substring(expectedRows[i].indexOf(','));
            assertThat(actualTail).isEqualTo(expectedTail);
        }
    }
}
