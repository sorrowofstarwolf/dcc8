# 动态脱敏竞赛基线算法说明

## 目标

本实现基于 Java 11、Spring Boot 2.7.x 和 Maven，提供 `/health` 与 `/encrypt` 两个接口。目标是在满足结果 100% 正确的前提下，尽量降低 100 并发请求下的 CSV 加密与写盘耗时。

核心约束是：`/health` 被调用之前不能读取原始 CSV、不能申请加密业务大内存、不能初始化加密服务。因此数据加载被设计为首次 `/encrypt` 请求触发的惰性加载。

## 整体流程

1. 应用启动，只初始化 Spring Web、配置类和 Controller。
2. `/health` 直接返回 `{"status":"SUCCESS"}`，不触发任何业务初始化。
3. 第一次 `/encrypt` 到来时，`DataStore` 使用 `CompletableFuture` 触发唯一一次 CSV 加载。
4. 并发到来的其他 `/encrypt` 请求等待同一个加载结果，避免重复读盘和重复构建内存结构。
5. 加载完成后，所有请求共享同一份列式内存数据。
6. 每个请求根据自己的 `sm4Key` 和 `fieldsToEncrypt` 生成独立 CSV 文件。
7. 输出文件 `flush/close` 后再执行回调，避免验证程序读取半文件。

## 数据结构

数据按列存储，不保存每个单元格的 `String` 对象。

每一列使用：

- `byte[] pool`：保存字段字节内容。
- `int[] offsets`：每行该字段在 pool 中的起始偏移。
- `int[] lengths`：每行该字段的字节长度。

SM4 字段进入原文字节池：

- `user_id`
- `serial_no`
- `user_code`
- `business_key`
- `device_id`
- `trans_id`
- `secret_code`

掩码字段进入掩码字节池：

- `id_card`
- `phone`
- `name`
- `email`

掩码字段与请求密钥无关，所以在 CSV 加载阶段预计算最终结果；后续请求直接写出预计算字节。

## CSV 加载

正式题目数据字段顺序固定，当前实现使用专用快速解析器按逗号扫描。这样比通用 CSV parser 更少对象、更少分支。

加载时会跳过表头，逐行解析 11 个字段。每个字段只在加载阶段复制到预分配字节池一次。字节池满时不会自动扩容，而是抛出异常，提示调大配置容量。这是为了避免运行期复制大数组造成长尾抖动。

如果正式数据包含复杂 CSV 转义、引号或字段内换行，应替换加载阶段解析器，但加载后的列式结构不需要改变。

## 掩码规则

掩码按字符处理，不能按 UTF-8 字节处理，避免中文被截断。

- 长度小于等于 6：`首字符 + #####`
- 长度大于 6：`首字符 + #### + 尾字符`

示例：

- `1823 -> 1#####`
- `何军 -> 何#####`
- `zhangsan@qq.com -> z####m`

## SM4 加密

每个请求只创建一个 SM4 上下文，复用 `Cipher`、`SecretKeySpec` 和 `IvParameterSpec`。

加密参数严格按题目要求：

- 算法：`SM4/CBC/PKCS5Padding`
- Provider：BouncyCastle
- IV：`1234567890123456`
- Key：请求中的 16 字节 `sm4Key`
- 输出：大写十六进制 ASCII

每个字段值独立加密，每次加密都从固定 IV 开始。密文转十六进制时直接写入复用的 `byte[]` 缓冲区，不生成中间 `String`。

## 请求处理

`fieldsToEncrypt` 会被转换为固定长度 `int[]` 字段编号数组。输出列顺序严格跟随请求中的字段顺序。

每个请求一个任务对象，任务内部复用：

- SM4 上下文
- 单元格输出缓冲
- 临时密文/hex 缓冲
- 低基数字段缓存
- 文件输出缓冲

输出 CSV：

- UTF-8
- 无 BOM
- 无表头
- 不加双引号
- 字段之间使用 `,`
- 行分隔使用 CRLF
- 文件末尾不额外追加空行

## 并发调度

HTTP 线程只负责接收请求、解析字段、提交任务。实际加密任务进入固定线程池。

默认配置：

- `workerThreads=3`
- `queueCapacity=128`

这样可以承接 100 并发请求，同时避免 100 个计算任务同时争抢 CPU、GC 和磁盘。

## 请求内缓存

相同请求内 `sm4Key` 相同，所以同一字段相同明文的密文也相同。当前实现只在同一请求内缓存低基数字段：

- `user_id`
- `user_code`
- `secret_code`

缓存使用固定容量开放寻址哈希表：

- 不使用 `HashMap`
- 不创建节点对象
- 不运行时扩容
- 命中后仍比较长度和原文字节，避免哈希碰撞导致错误

不同请求之间不共享密文缓存，因为不同请求的 `sm4Key` 可能不同。

## 配置项

主要配置在 `application.yml` 的 `dcc.*` 下：

- `team-code`
- `dataset-path`
- `output-dir`
- `callback-url`
- `worker-threads`
- `queue-capacity`
- `expected-rows`
- `initial-raw-pool-bytes`
- `initial-mask-pool-bytes`
- `output-buffer-bytes`
- `cache-capacity`
- `request-timeout-millis`
- `baseline-validation-enabled`

应用启动后会打印这些自定义配置，但不会打印真实 `sm4Key`。

## 正确性验证

官方样例请求：

```json
{
  "requestId": "REQ_20260413120000_0",
  "sm4Key": "2123433411630000",
  "ip": "55.51.53.74",
  "fieldsToEncrypt": ["phone", "user_code", "user_id", "name"]
}
```

测试会使用 `table_data_baseline.csv` 生成结果，并与 `result_baseline.csv` 比对。由于当前本地样例里的部分手机号被隐私系统替换，测试检测到该情况时会跳过无法还原的手机号列，其余列仍严格比对。

## 可复现实现要点

另一个实现者可以按以下顺序复现：

1. 使用 Spring Boot 提供 `/health` 和 `/encrypt`。
2. `/health` 不触发任何业务初始化。
3. 第一次 `/encrypt` 用单例异步加载 CSV。
4. 将 CSV 转为列式 `bytePool + offset + length`。
5. 加载时预计算掩码字段。
6. 请求到来时按字段顺序逐行输出。
7. SM4 字段用请求 key 即时加密。
8. 十六进制输出直接写 ASCII 字节。
9. 低基数字段只做同请求内缓存。
10. 文件 close 后再回调。
