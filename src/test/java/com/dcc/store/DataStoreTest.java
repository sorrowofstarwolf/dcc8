package com.dcc.store;

import com.dcc.config.AppProperties;
import com.dcc.domain.FieldId;
import com.dcc.service.MaskingService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DataStoreTest {
    @Test
    void loadsRowsAndFieldMappingOnce() {
        AppProperties properties = new AppProperties();
        properties.setDatasetPath("table_data_baseline.csv");
        properties.setExpectedRows(16);
        properties.setInitialRawPoolBytes(64 * 1024);
        properties.setInitialMaskPoolBytes(64 * 1024);

        DataStore dataStore = new DataStore(properties, new MaskingService());
        LoadedData first = dataStore.get();
        LoadedData second = dataStore.get();

        assertThat(first).isSameAs(second);
        assertThat(first.rows()).isEqualTo(10);
        ColumnData userCode = first.column(FieldId.USER_CODE);
        assertThat(new String(userCode.bytes(), userCode.offset(0), userCode.length(0), StandardCharsets.UTF_8)).isEqualTo("CZJE");
        assertThat(first.hasDictionary(FieldId.USER_CODE)).isTrue();
        assertThat(first.dictionary(FieldId.USER_CODE).uniqueCount()).isEqualTo(10);
    }
}
