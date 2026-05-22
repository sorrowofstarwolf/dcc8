# 压测基线报告

## 环境说明

本报告记录当前基线代码在本地模拟环境中的压测结果。测试目标是观察 30 万行数据、100 并发请求下的加密与写盘能力。

压测脚本：

```powershell
.\scripts\run_contest_perf.ps1
```

注意：压测生成的 CSV 输入数据和输出目录不进入 git 仓库。

## 构建与正确性

已执行：

```text
mvn test    -> BUILD SUCCESS, Tests run: 6, Failures: 0
mvn package -> BUILD SUCCESS
/health     -> {"status":"SUCCESS"}
```

## 高重复数据压测

数据：

- 文件：`perf_data_300000.csv`
- 行数：300,000
- 请求数：100
- 字段：`phone,user_code,user_id,name`
- workerThreads：3
- 回调：关闭

结果：

| 指标 | 数值 |
|---|---:|
| HTTP 100 请求提交耗时 | 169 ms |
| 全部输出完成耗时 | 9.24 s |
| 输出文件数 | 100 |
| 总输出大小 | 2317.43 MB |
| 平均单文件大小 | 23.17 MB |
| 输出吞吐 | 250.67 MB/s |

进度：

```text
1.19s  12 files   208.57 MB
2.20s  33 files   695.23 MB
3.20s  54 files   1181.89 MB
4.21s  74 files   1645.37 MB
5.22s  96 files   2155.21 MB
6.22s  100 files  2317.43 MB
9.24s  文件大小稳定，判定完成
```

## 低缓存命中数据压测

数据：

- 文件：`perf_data_low_cache_300000.csv`
- 行数：300,000
- `user_id`：5 位数字循环，允许重复
- `user_code`：300,000 个唯一值
- `name`：300,000 个唯一值
- 请求数：100
- 字段：`phone,user_code,user_id,name`
- workerThreads：3
- 回调：关闭

结果：

| 指标 | 数值 |
|---|---:|
| HTTP 100 请求提交耗时 | 176 ms |
| 全部输出完成耗时 | 14.27 s |
| 输出文件数 | 100 |
| 总输出大小 | 2317.43 MB |
| 平均单文件大小 | 23.17 MB |
| 输出吞吐 | 162.40 MB/s |

进度：

```text
1.19s   6 files    69.52 MB
2.20s   15 files   326.09 MB
3.20s   27 files   556.18 MB
5.22s   47 files   1036.67 MB
7.23s   67 files   1516.15 MB
9.24s   87 files   1946.64 MB
11.25s  100 files  2317.43 MB
14.27s  文件大小稳定，判定完成
```

## CSV 加载耗时

使用低缓存命中 30 万行数据，单请求补测服务内部日志：

```text
Loaded dataset rows=300000, rawPoolUsed=21788890, maskPoolUsed=7200000, millis=278
requestId=LOADTIME_20260523005543, fields=4, keyLength=16, rows=300000, millis=716
```

含义：

- CSV 读取、解析、掩码预计算、列式写入总耗时：278 ms
- 单请求总耗时：716 ms
- 原文字节池使用：约 20.78 MB
- 掩码字节池使用：约 6.87 MB

## 完成判定口径

压测脚本从外部观察输出目录：

1. 等待目标数量的 `.csv` 文件出现。
2. 每秒统计一次总文件大小。
3. 文件数达到请求数，且总大小连续 3 次不再变化时，判定完成。

该口径不依赖服务内部状态，适合模拟验证程序读取输出文件的视角。

## 结论

当前基线在高重复数据下缓存收益明显，100 个 30 万行请求约 9.24 秒完成；在 `user_code/name` 基本无缓存命中的情况下，约 14.27 秒完成。CSV 首次加载本身不是瓶颈，主要耗时集中在 SM4 加密与大量文件写盘。
