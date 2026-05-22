package com.dcc.store;

final class BytePool {
    private byte[] data;
    private int position;

    BytePool(int capacity) {
        this.data = new byte[capacity];
    }

    int append(byte[] source, int offset, int length) {
        // 字节池只做顺序追加，返回起始偏移；字段值通过 offset/length 引用，避免每个单元格创建对象。
        ensure(length);
        int start = position;
        System.arraycopy(source, offset, data, position, length);
        position += length;
        return start;
    }

    int append(byte[] source) {
        return append(source, 0, source.length);
    }

    byte[] array() {
        return data;
    }

    int position() {
        return position;
    }

    private void ensure(int needed) {
        if (position + needed <= data.length) {
            return;
        }
        // 这里故意不自动扩容：正式环境应通过配置一次性预分配足够容量，避免比赛热路径出现复制大数组的抖动。
        throw new IllegalStateException("BytePool capacity exceeded. Increase configured preallocated pool size.");
    }
}
