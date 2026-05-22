param(
    [int]$Port = 18085,
    [int]$Requests = 100,
    [int]$RowsPerRequest = 300000,
    [int]$WorkerThreads = 3,
    [string]$DatasetPath = "perf_data_300000.csv",
    [string]$FieldsJson = '["phone","user_code","user_id","name"]'
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

# 每次压测前先删除上一轮生成的 CSV 输出目录，避免旧文件影响完成时间统计，也释放磁盘空间。
$patterns = @("contest-output-*", "perf-output-*")
foreach ($pattern in $patterns) {
    Get-ChildItem -Path $root -Directory -Filter $pattern -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.FullName.StartsWith($root)) {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }
    }
}

# 删除目录后主动触发 PowerShell 侧 GC；Java 服务进程会在 finally 中停止，JVM 堆内存随进程退出释放。
[GC]::Collect()
[GC]::WaitForPendingFinalizers()

Add-Type -AssemblyName System.Net.Http

$runId = Get-Date -Format "yyyyMMddHHmmss"
$outDir = "contest-output-$runId"
New-Item -ItemType Directory -Path $outDir | Out-Null

$argsList = @(
    "-jar", "target\dcc-1.0-SNAPSHOT.jar",
    "--server.port=$Port",
    "--dcc.dataset-path=$DatasetPath",
    "--dcc.output-dir=$outDir",
    "--dcc.expected-rows=$RowsPerRequest",
    "--dcc.initial-raw-pool-bytes=67108864",
    "--dcc.initial-mask-pool-bytes=33554432",
    "--dcc.worker-threads=$WorkerThreads",
    "--dcc.queue-capacity=128",
    "--dcc.cache-capacity=524288",
    "--dcc.callback-url="
)

$proc = Start-Process -FilePath java -ArgumentList $argsList -PassThru -WindowStyle Hidden
try {
    Start-Sleep -Seconds 6
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(300)
    $tasks = New-Object "System.Collections.Generic.List[System.Threading.Tasks.Task[System.Net.Http.HttpResponseMessage]]"

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt $Requests; $i++) {
        $json = '{"requestId":"CONTEST_' + $runId + '_' + $i + '","sm4Key":"2123433411630000","ip":"55.51.53.74","fieldsToEncrypt":' + $FieldsJson + '}'
        $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, "application/json")
        $tasks.Add($client.PostAsync("http://127.0.0.1:$Port/encrypt", $content))
    }
    [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
    $postMs = $sw.ElapsedMilliseconds

    $deadline = [DateTime]::UtcNow.AddSeconds(600)
    $lastBytes = -1L
    $stable = 0
    do {
        Start-Sleep -Seconds 1
        $files = Get-ChildItem -Path $outDir -Filter "*.csv" -ErrorAction SilentlyContinue
        $bytes = ($files | Measure-Object -Property Length -Sum).Sum
        if ($files.Count -eq $Requests -and $bytes -eq $lastBytes -and $bytes -gt 0) {
            $stable++
        } else {
            $stable = 0
            $lastBytes = $bytes
        }
        Write-Output ("progress files={0} mb={1:N2} elapsedMs={2}" -f $files.Count, ($bytes / 1MB), $sw.ElapsedMilliseconds)
    } while ($stable -lt 3 -and [DateTime]::UtcNow -lt $deadline)

    $sw.Stop()
    $files = Get-ChildItem -Path $outDir -Filter "*.csv" -ErrorAction SilentlyContinue
    $bytes = ($files | Measure-Object -Property Length -Sum).Sum
    [PSCustomObject]@{
        Health = $health.Content
        OutputDir = $outDir
        Requests = $Requests
        RowsPerRequest = $RowsPerRequest
        WorkerThreads = $WorkerThreads
        HttpPostMs = $postMs
        CompleteMs = $sw.ElapsedMilliseconds
        CompleteSeconds = [Math]::Round($sw.ElapsedMilliseconds / 1000, 2)
        Files = $files.Count
        TotalOutputMB = [Math]::Round($bytes / 1MB, 2)
        AvgFileMB = [Math]::Round(($bytes / [Math]::Max(1, $files.Count)) / 1MB, 2)
        ThroughputMBps = [Math]::Round(($bytes / 1MB) / ($sw.ElapsedMilliseconds / 1000), 2)
    } | Format-List
}
finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id
    }
    # 压测服务进程停止后 JVM 内存会被操作系统回收；这里再清一次 PowerShell 侧临时对象。
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
