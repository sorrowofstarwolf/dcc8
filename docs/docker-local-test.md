# Docker 本地 4C8G 测试

这个项目可以通过 Docker 在本机模拟 `4C8G` 运行环境。资源限制由 `docker-compose.yml` 中的 `cpus: 4` 和 `mem_limit: 8g` 控制。

## 1. 构建镜像

```powershell
docker compose build
```

如果基础镜像拉取较慢，请先处理 Docker Desktop 的网络或镜像加速配置。

## 2. 启动项目

```powershell
docker compose up -d
```

健康检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/health -UseBasicParsing
```

## 3. 调整数据集或参数

默认挂载本地 `table_data_baseline.csv` 到容器内 `/opt/app/dcc/table_data_baseline.csv`。

如果要切换更大的数据集，可以修改 `docker-compose.yml` 中这两项：

- `DCC_DATASET_PATH`
- `volumes` 里的数据文件挂载

其他运行参数同样通过 `environment` 配置，例如：

- `DCC_WORKER_THREADS`
- `DCC_QUEUE_CAPACITY`
- `DCC_EXPECTED_ROWS`

## 4. 查看输出文件

容器内输出目录 `/opt/app/dcc/output` 已挂载到宿主机 `output/`，加密结果会直接出现在项目根目录下的 `output` 文件夹。

## 5. 停止服务

```powershell
docker compose down
```

## 6. Extreme 压测与 JFR

如果要模拟比赛压测流程，建议使用仓库内的脚本：

```powershell
.\scripts\run_docker_perf.ps1 -InContainerLoadTest
```

如果你在 WSL / Git Bash 里跑，也可以用：

```bash
sh ./scripts/run_docker_perf.sh
```

这条流程会完成几件事：

- 使用 Docker 构建镜像
- 以 `4 CPU / 8 GB` 资源限制启动容器
- 在容器内用 `nohup java -XX:StartFlightRecording=...` 后台启动项目
- 在容器内发起 `100` 个并发请求，绕过宿主机到 Docker 的并发端口映射
- 默认请求字段为 `7` 个 SM4 字段加 `1` 个掩码字段：
  `user_id, serial_no, user_code, business_key, device_id, trans_id, secret_code, name`
- 停止容器并导出 JFR 文件
- 自动生成一份 `jfr summary`
- 自动在容器内清理本轮输出 CSV

输出文件位置：

- JFR：`perf-reports/*.jfr`
- JFR 摘要：`perf-reports/*.summary.txt`
- JFR 关键事件：`perf-reports/*.events.txt`
- 应用日志：`perf-reports/app.log`
- 加密结果：`perf-output/*.csv`
