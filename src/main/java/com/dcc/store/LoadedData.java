package com.dcc.store;

public final class LoadedData {
    private final ColumnData[] columns;
    private final int rows;

    LoadedData(ColumnData[] columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public ColumnData column(int fieldId) {
        return columns[fieldId];
    }

    public int rows() {
        return rows;
    }
}
