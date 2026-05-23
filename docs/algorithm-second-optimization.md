# 第二轮优化算法说明

## 目标

第二轮优化基于第一版基线实现继续降低 100 并发请求下的 SM4 加密与 CSV 写盘耗时。核心原则保持不变：

- `/health` 被调用之前不读取 CSV，不申请业务大内存，不初始化加密业务数据。
- 第一次 `/encrypt` 才触发 CSV 惰性加载。
- 输出 CSV 的字段顺序、编码、换行和密文格式保持与基线一致。
- 每个请求仍然使用自己的 `sm4Key`，不同请求之间不共享密文。

本轮没有改变 REST 接口和回调语义，主要优化加载后的内存结构、请求内加密路径和文件输出路径。

## 相对基线的主要变化

### 1. 加载期 SM4 字段字典化

基线实现对低基数字段使用请求内 `FixedCipherCache`，即每个请求在逐行写出时边加密边缓存。第二轮将这部分重复关系提前到 CSV 加载阶段整理。

当前默认字典化字段沿用基线中的低基数字段判断：

- `user_id`
- `user_code`
- `secret_code`

加载 CSV 时，仍然把 SM4 原文字节写入 `rawPool`，同时为上述字段建立字段级唯一值字典：

- `uniqueOffsets`：唯一原文值在 `rawPool` 中的起始位置。
- `uniqueLengths`：唯一原文值长度。
- `rowValueIds`：每一行对应的唯一值编号。
- `hashes`：开放寻址哈希表，用于加载时去重。

这样请求阶段不再需要对这些字段逐行查缓存，而是可以先把唯一值加密一次，再按行复用密文。

### 2. 请求期字典字段批量加密

每个 `/encrypt` 请求创建自己的 `Sm4Cipher.Context`。对于本次请求包含的字典化 SM4 字段，先遍历该字段的唯一值集合：

1. 使用当前请求的 `sm4Key` 加密唯一原文。
2. 将密文直接转为大写 HEX 字节。
3. 写入请求级密文字节池。
4. 记录每个唯一值对应的密文偏移和长度。

随后逐行输出时，如果字段已字典化：

1. 通过 `rowValueIds[row]` 找到唯一值编号。
2. 从请求级密文字节池复制对应密文。
3. 不再执行 SM4 加密。

因此，对高重复字段，单请求 SM4 次数从：

```text
rows
```

下降为：

```text
uniqueValues
```

对 100 个并发请求，这个收益会被请求数放大。

### 3. 保留非字典字段原路径

非字典化 SM4 字段仍走基线的逐行即时加密路径。`FixedCipherCache` 仍保留给未字典化但符合低基数判断的路径使用，保证配置关闭字典化时仍能回到基线行为。

新增配置：

```yaml
dcc:
  dictionary-enabled: true
```

当 `dcc.dictionary-enabled=false` 时，加载期不建立字典，服务回退到原始列式数据 + 请求内缓存的路径。

### 4. 快速 CSV 写出

基线依赖 `BufferedOutputStream` 并在热路径中频繁调用：

```text
write(',')
write('\r')
write('\n')
write(cellBytes)
```

第二轮新增 `FastCsvWriter`，每个请求持有一个固定大小 byte buffer。逗号、CRLF 和字段内容先写入该 buffer，buffer 满后再批量写入底层文件流。

该优化减少了大量细碎 `OutputStream.write` 调用，对字典化和非字典化路径都生效。

### 5. 固定缓存满表保护

基线 `FixedCipherCache` 使用开放寻址哈希表。第二轮为 `get` 和 `put` 增加探测上限，避免极端情况下哈希表满载导致无限循环。该改动不改变缓存命中语义，只提升边界可靠性。

## 当前处理流程

### CSV 加载

```text
第一次 /encrypt
  -> DataStore.get()
  -> 读取 CSV
  -> 掩码字段预计算并写入 maskPool
  -> SM4 字段原文写入 rawPool
  -> 对 user_id/user_code/secret_code 建立 DictionaryColumnData
  -> 返回 LoadedData
```

### 单请求生成

```text
EncryptTask
  -> 创建当前请求 SM4 Context
  -> 对请求字段中的字典字段批量加密 uniqueValues
  -> 遍历每一行
      -> 掩码字段：直接写预计算结果
      -> 字典 SM4 字段：按 rowValueId 写请求级密文
      -> 普通 SM4 字段：即时 SM4 加密后写出
  -> flush/close 输出文件
  -> 回调验证程序
```

## 正确性约束

- 字典化只保存原文重复关系，不提前计算最终密文。
- 最终密文仍在请求到达后使用当前 `sm4Key` 计算。
- 不同请求之间不共享密文字节池。
- 字典字段与非字典字段输出格式完全一致。
- 字段顺序严格跟随 `fieldsToEncrypt`。
- 文件仍为 UTF-8、无 BOM、无表头、CRLF 换行、末尾无额外空行。

## 验证

本地环境没有可用 `mvn` 命令，因此使用本地 Maven 仓库依赖做了 Java 11 编译检查：

- 主代码编译通过。
- 测试代码编译通过。
- baseline 小样本端到端输出验证通过：字典化开启后，生成结果与 `result_baseline.csv` 的可比对列一致。

`DataStoreTest` 增加了字典结构断言，确认默认配置下 `user_code` 会建立字典列。
