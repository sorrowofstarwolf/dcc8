# 第三轮优化性能报告

## 环境说明

本报告记录第三轮 SM4 本体优化后的本地压测结果。测试环境与第二轮保持同一台本地机器、同一数据文件和同一 direct core benchmark 口径。

由于当前 shell 环境没有可用 `mvn` 命令，验证方式为：

- 使用本地 Maven 仓库依赖手工执行 Java 11 `javac` 编译。
- 通过临时 direct benchmark 类直接调用 `EncryptService.generateBlocking`。
- direct benchmark 覆盖 CSV 加载、字典构建、SM4 加密、CSV 写出，不包含 HTTP 提交开销。

## 正确性检查

本轮完成以下检查：

```text
主代码 javac 编译检查        -> 通过
SM4 标准实现逐长度比对       -> 通过
baseline 小样本端到端验证    -> 通过
```

SM4 标准对照使用 BouncyCastle `SM4/CBC/PKCS5Padding`，覆盖 0 到 64 字节明文：

```text
VERIFY_SM4_BC_OK lengths=0..64
```

端到端小样本验证：

```text
VERIFY_FAST_OUTPUT_OK rows=10 bytes=828
```

## 压测口径

### 极限低重复 SM4 数据

数据集：

```text
perf_data_extreme_unique_300000.csv
```

生成脚本：

```text
scripts/generate_extreme_perf_data.ps1
```

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

参数：

| 配置 | 数值 |
|---|---:|
| 请求数 | 100 |
| 每请求行数 | 300000 |
| workerThreads | 3 |
| dictionary-enabled | true |
| JVM | `-Xms512m -Xmx2g` |
| 总输出 | 8468.63 MB |

### 非极限数据

请求字段：

```json
["phone", "user_code", "user_id", "name"]
```

说明：

- `phone`、`name` 为掩码字段，加载期预计算。
- `user_code`、`user_id` 为 SM4 字段。
- 请求数、行数和 workerThreads 与极限测试一致。

## 关键阶段性能变化

### 极限低重复 7 字段

该场景最能放大真实 SM4 本体开销。

| 实现阶段 | 完成耗时 | 总输出 | 吞吐 |
|---|---:|---:|---:|
| 第二轮 JCE `Cipher` 基线 | 37.76 s | 8468.63 MB | 224.27 MB/s |
| BouncyCastle lightweight 通用 CBC | 22.09 s | 8468.63 MB | 383.37 MB/s |
| lightweight 短字段路径 | 20.21 s | 8468.63 MB | 419.03 MB/s |
| 内联 SM4 block encrypt | 19.74 s | 8468.63 MB | 428.97 MB/s |
| T 表轮函数 | 14.92 s | 8468.63 MB | 567.56 MB/s |
| 字典直写 + 4/5/6 字节专用路径 | **14.55 s** | 8468.63 MB | **581.88 MB/s** |

相对第二轮 JCE 基线：

```text
耗时下降约 61.5%
吞吐提升约 159.5%
```

相对 T 表前的内联 block encrypt：

```text
19.74 s -> 14.92 s
耗时下降约 24.4%
```

最后一轮收尾优化：

```text
14.92 s -> 14.55 s
耗时下降约 2.5%
```

## 非极限数据对比

### 低缓存命中数据

数据集：

```text
perf_data_low_cache_300000.csv
```

| 实现阶段 | 完成耗时 | 总输出 | 吞吐 |
|---|---:|---:|---:|
| 第二轮字典优化 | 7.59 s | 2317.43 MB | 305.53 MB/s |
| T 表轮函数 | 3.20 s | 2317.43 MB | 724.65 MB/s |
| 字典直写 + 4/5/6 字节专用路径 | **3.14 s** | 2317.43 MB | **738.50 MB/s** |

相对第二轮：

```text
耗时下降约 58.6%
吞吐提升约 141.7%
```

### 常规重复率数据

数据集：

```text
perf_data_300000.csv
```

| 实现阶段 | 完成耗时 | 总输出 | 吞吐 |
|---|---:|---:|---:|
| 第二轮字典优化 | 6.22 s | 2317.43 MB | 372.76 MB/s |
| T 表轮函数 | 1.66 s | 2317.43 MB | 1398.57 MB/s |
| 字典直写 + 4/5/6 字节专用路径 | **1.69 s** | 2317.43 MB | **1368.02 MB/s** |

说明：常规数据重复率高，字典化已显著减少真实 SM4 次数，最后一轮 4/5/6 字节专用路径收益被调度和写文件波动覆盖，结果与 T 表版本基本持平。

## 结论

第三轮优化确认当前性能瓶颈曾经主要集中在 SM4 本体。将 JCE `Cipher` 路径替换为专用 SM4 热路径，并使用 T 表折叠轮函数后，极限低重复 7 字段场景从 37.76 秒降到 14.55 秒。

本轮最大收益来自：

1. 去掉 JCE `Cipher.init/doFinal` 的通用调用成本。
2. 内联 SM4 block encrypt，减少中间数组和调用边界。
3. 使用 T 表减少每轮 rotate/xor 运算。

最后的字典直写和 4/5/6 字节专用路径收益较小，说明 SM4 算法层面已经进入细节压榨阶段。继续完全展开 32 轮可能还能获得少量收益，但代码体积和维护成本会上升，当前不建议继续把主要精力投入 SM4 本体。

