package com.dcc.store;

public final class ColumnData {
    private final BytePool pool;
    // 每一行只保存该字段在共享字节池中的起点和长度，内存结构稳定且便于顺序扫描。
    private final int[] offsets;
    private final int[] lengths;

    ColumnData(BytePool pool, int rows) {
        this.pool = pool;
        this.offsets = new int[rows];
        this.lengths = new int[rows];
    }

    void put(int row, byte[] source, int offset, int length) {
        // 加载阶段完成一次复制，后续请求直接按偏移读取，不再解析 CSV 或生成字段字符串。
        offsets[row] = pool.append(source, offset, length);
        lengths[row] = length;
    }

    void put(int row, byte[] source) {
        put(row, source, 0, source.length);
    }

    public byte[] bytes() {
        return pool.array();
    }

    public int offset(int row) {
        return offsets[row];
    }

    public int length(int row) {
        return lengths[row];
    }
}
