package com.dcc.store;

public final class LoadedData {
    private final ColumnData[] columns;
    private final DictionaryColumnData[] dictionaries;
    private final int rows;

    LoadedData(ColumnData[] columns, DictionaryColumnData[] dictionaries, int rows) {
        this.columns = columns;
        this.dictionaries = dictionaries;
        this.rows = rows;
    }

    public ColumnData column(int fieldId) {
        return columns[fieldId];
    }

    public DictionaryColumnData dictionary(int fieldId) {
        return dictionaries[fieldId];
    }

    public boolean hasDictionary(int fieldId) {
        return dictionaries[fieldId] != null;
    }

    public int rows() {
        return rows;
    }
}
