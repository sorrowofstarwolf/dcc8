# Docker 本地 4C8G 压测规范

本文档用于固定项目的 Docker 压测方式，避免每次压测时因为数据集、请求字段、并发口径、保留产物或环境差异而导致结果不可比。

## 1. 标准压测口径

所有标准 Docker 压测统一使用以下参数：

- 并发数：`100`
- 请求数：`100`
- 数据集：`perf_data_extreme_unique_300000.csv`
- 请求前缀：`EXTREME`
- 字段集合：`["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]`
- 输出状态轮询间隔：`1s`
- 输出稳定判定：连续 `3` 次轮询中，`EXTREME_*.csv` 的文件数为 `100` 且总大小不再变化

其中：

- `user_id`
- `serial_no`
- `user_code`
- `business_key`
- `device_id`
- `trans_id`
- `secret_code`

以上 `7` 个字段走 `SM4` 加密。

- `name`

以上 `1` 个字段走掩码处理。

## 2. 保留产物

每次标准压测必须保留以下诊断产物：

- `perf-reports/*.jfr`
- `perf-reports/*.summary.txt`
- `perf-reports/*.events.txt`
- `perf-reports/*.run.txt`
- `perf-reports/app.log`

其中 `*.run.txt` 是每轮压测的速度摘要，至少包含：

- `elapsedSeconds`
- `requestsPerSecond`
- `throughputMBps`
- `totalCsvFiles`
- `totalCsvBytes`

`*.events.txt` 中至少要能看到以下事件：

- `jdk.CPULoad`
- `jdk.GarbageCollection`
- `jdk.ThreadPark`
- `jdk.ExecutionSample`

## 3. 不保留产物

压测过程中会生成 `100` 个输出 CSV，但这些文件不作为最终保留物。

只允许把它们用于以下目的：

- 观察文件数量是否达到 `100`
- 观察总大小是否稳定
- 记录本轮输出总字节数

完成上述观察后，必须清理宿主机和容器内的输出 CSV，只保留 JFR 和指标文件。

## 4. 标准执行命令

推荐命令：

```powershell
.\scripts\run_docker_perf.ps1 -InContainerLoadTest
```

如果本地已经有可用的 `dcc:local` 镜像，而当前环境无法重新拉取基础镜像，可以临时使用：

```powershell
.\scripts\run_docker_perf.ps1 -InContainerLoadTest -SkipImageBuild
```

WSL / Git Bash 可以使用：

```bash
sh ./scripts/run_docker_perf.sh
```

## 5. 标准流程

每次压测严格按下面顺序执行：

1. 先看 `git status --short`，确认当前代码状态，并记录是否存在会影响性能结论的本地修改。
2. 确认 Docker 可用，且不存在残留的 `dcc-perf-app` 容器。
3. 清理旧的临时输出目录，避免上一轮 `CSV` 结果干扰统计。
4. 构建当前代码对应的 jar 和镜像；如果镜像源异常但本地已有 `dcc:local`，允许使用 `-SkipImageBuild`。
5. 以 `4 CPU / 8 GB` 限制启动容器，并从 JVM 启动时开启 `JFR`。
6. 等待 `http://127.0.0.1:18080/health` 返回成功后，再开始发送加密请求。
7. 在容器内发起固定 `100` 并发、固定字段集合、固定 `EXTREME` 前缀的请求。
8. 轮询输出目录，只观察 `EXTREME_*.csv` 的数量和总大小，直到数量达到 `100` 且大小连续稳定。
9. 导出 `JFR` 文件、`summary`、`events` 和本轮运行摘要。
10. 清理容器内和宿主机输出目录中的 `CSV` 文件。
11. 停止容器。
12. 后续分析只基于保留下来的 JFR、日志和摘要，不再依赖 CSV 文件。

程序执行速度统一以 `*.run.txt` 中的以下指标为准：

- `elapsedSeconds`：从开始发起固定压测请求，到输出文件满足“连续 3 次、每次间隔 1 秒、文件数为 100 且总大小不再变化”为止的总执行时间。
- `requestsPerSecond`：本轮标准 `100` 请求对应的平均请求吞吐。
- `throughputMBps`：本轮输出总字节数折算出的平均输出吞吐。

## 6. 压测时重点观察什么

每次回看 JFR 时，至少检查以下内容：

- `GC` 次数、暂停时间、分配压力和堆行为。
- `ThreadPark` 事件，判断是否存在队列等待、线程池争用、写文件阻塞或其他 park 行为。
- 工作线程、异步写线程、HTTP 线程的 CPU 使用情况。
- `ExecutionSample` 中的热点方法，重点关注 `SM4` 加密、CSV 行构造、缓存命中、缓冲区写入和文件 I/O。
- 当前线程数配置是否压满了 `4 CPU`，还是存在明显闲置或过度争抢。

## 7. 已验证状态

当前仓库中的 `scripts/run_docker_perf.ps1` 已经按这套口径收紧：

- 固定要求 `100` 并发
- 固定要求 `100` 请求
- 固定要求 `perf_data_extreme_unique_300000.csv`
- 固定要求 `7` 个 `SM4` 字段加 `1` 个掩码字段
- 固定导出 `GC`、`ThreadPark`、CPU 和热点样本事件
- 固定在压测结束后清理输出 CSV
- 固定生成 `*.run.txt` 记录本轮参数和输出统计

## 8. 失败处理原则

如果压测失败，不要直接把失败结果和上一轮性能结论混在一起。先记录失败发生在哪一步，再修复环境问题，然后完整重跑整套流程。

特别是以下问题，必须单独标记为环境问题：

- Docker 基础镜像拉取失败
- 端口占用
- 容器残留
- 数据集挂载错误
- 本地旧输出未清理
- JFR 未成功导出

只有完整走完标准流程的一轮结果，才作为可对比的性能基线。
