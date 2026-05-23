# 极限 SM4 低重复数据压测基线

## 目的

该压测用于隔离和放大 SM4 加密本身的成本，为后续优化 SM4 实现提供对照基线。

常规压测字段通常包含掩码字段和低基数 SM4 字段，字典化会显著减少 SM4 次数。为了观察真实 SM4 热路径，本次构造了一个 30 万行、11 字段齐全、低重复率的数据集，并在请求中选择全部 7 个 SM4 字段。

## 数据集

文件：

```text
perf_data_extreme_unique_300000.csv
```

生成脚本：

```text
scripts/generate_extreme_perf_data.ps1
```

生成命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate_extreme_perf_data.ps1 -Rows 300000 -OutputPath perf_data_extreme_unique_300000.csv
```

数据规模：

| 指标 | 数值 |
|---|---:|
| 数据行数 | 300000 |
| 总行数 | 300001 |
| 文件大小 | 42.34 MB |
| 字段数 | 11 |

唯一度设计：

| 字段 | 处理方式 | 唯一度 |
|---|---|---:|
| user_id | 5 位数字格式限制 | 100000 |
| serial_no | 13 位数字递增 | 300000 |
| user_code | 4 位 base26 编码 | 300000 |
| business_key | 递增组合字符串 | 300000 |
| id_card | 18 位递增模拟值 | 300000 |
| phone | 11 位递增模拟值 | 300000 |
| name | ASCII 名称递增 | 300000 |
| email | 递增邮箱 | 300000 |
| device_id | 递增 MAC 格式 | 300000 |
| trans_id | 18 位数字递增 | 300000 |
| secret_code | 6 位 base36 编码 | 300000 |

说明：`user_id` 受题目格式约束为 5 位数字，因此最多只能构造 100000 个唯一值；30 万行下每个值约重复 3 次。其他字段均构造为 30 万唯一值。

## 压测配置

请求字段为全部 7 个 SM4 字段：

```json
[
  "user_id",
  "serial_no",
  "user_code",
  "business_key",
  "device_id",
  "trans_id",
  "secret_code"
]
```

压测参数：

| 配置 | 数值 |
|---|---:|
| 请求数 | 100 |
| 每请求行数 | 300000 |
| workerThreads | 3 |
| dictionary-enabled | true |
| JVM | `-Xms512m -Xmx2g` |
| 输出目录 | `target/direct-extreme-current` |

压测方式：

- 直接调用 `EncryptService.generateBlocking`。
- 覆盖 CSV 加载、字典化、SM4 加密、CSV 写盘。
- 不包含 HTTP 提交开销。

## 加载期统计

加载日志中的字典唯一值：

| 字段 | 唯一值数量 |
|---|---:|
| user_id | 100000 |
| user_code | 300000 |
| secret_code | 300000 |

CSV 加载耗时：

```text
338 ms
```

## 压测结果

| 指标 | 数值 |
|---|---:|
| 完成时间 | 37.76 s |
| 输出文件 | 100 |
| 总输出 | 8468.63 MB |
| 输出吞吐 | 224.27 MB/s |

## 结论

该场景下输出量约 8.47 GB，且 7 个字段全部走 SM4。由于大部分 SM4 字段接近全唯一，字典化对 `user_code` 和 `secret_code` 基本无法减少加密次数，性能主要受以下因素共同限制：

1. 大量真实 SM4 加密。
2. 请求级字典密文构建。
3. 8.47 GB CSV 写盘。
4. CSV 字段拼接和内存复制。

这组结果可作为后续 SM4 优化的基准。若替换 JCE `Cipher.init/doFinal` 为更轻量的 SM4 实现，建议使用同一数据集和同一请求字段重新压测，对比完成时间与吞吐变化。
