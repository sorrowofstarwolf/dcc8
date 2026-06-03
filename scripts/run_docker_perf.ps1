param(
    [string]$AppContainer = "dcc-perf-app",
    [string]$ImageName = "dcc:local",
    [int]$HostPort = 18080,
    [int]$Concurrency = 100,
    [int]$Requests = 100,
    [string]$DatasetHostPath = ".\\perf_data_extreme_unique_300000.csv",
    [string]$DatasetContainerPath = "/opt/app/dcc/perf_data_extreme_unique_300000.csv",
    [string]$OutputDirHost = ".\\perf-output",
    [string]$ReportDirHost = ".\\perf-reports",
    [string]$AppJarHostPath = ".\\target\\dcc-1.0-SNAPSHOT.jar",
    [string]$FieldsJson = '["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]',
    [int]$PollIntervalSeconds = 1,
    [int]$OutputBufferBytes = 94371840,
    [int]$AsyncWriteQueueSlots = 20,
    [int]$AsyncWriteWorkerThreads = 8,
    [switch]$InContainerLoadTest,
    [switch]$SkipImageBuild
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

function Assert-LastExitCode {
    param(
        [string]$Step
    )
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$datasetPath = (Resolve-Path $DatasetHostPath).Path
$appJarPath = (Resolve-Path $AppJarHostPath).Path
$runId = Get-Date -Format "yyyyMMddHHmmss"
$outputDirRoot = Join-Path $root $OutputDirHost.TrimStart(".\")
$outputDir = Join-Path $outputDirRoot ("run-" + $runId)
$reportDir = Join-Path $root $ReportDirHost.TrimStart(".\")
$jfrName = "perf-" + $runId
$standardFieldsJson = '["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]'

if ($Concurrency -ne 100) {
    throw "standard docker pressure test requires Concurrency=100"
}
if ($Requests -ne 100) {
    throw "standard docker pressure test requires Requests=100"
}
if ($FieldsJson -ne $standardFieldsJson) {
    throw "standard docker pressure test requires the fixed 7 SM4 + 1 mask field request set"
}
if ([System.IO.Path]::GetFileName($datasetPath) -ne "perf_data_extreme_unique_300000.csv") {
    throw "standard docker pressure test requires dataset perf_data_extreme_unique_300000.csv"
}

New-Item -ItemType Directory -Force -Path $outputDirRoot | Out-Null
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

if (-not $SkipImageBuild) {
    mvn -q -DskipTests package
    Assert-LastExitCode "mvn package"
    docker build --pull=false -t $ImageName .
    Assert-LastExitCode "docker build"
}
$existingContainer = docker ps -aq -f "name=^${AppContainer}$"
if ($existingContainer) {
    docker rm -f $AppContainer | Out-Null
    Assert-LastExitCode "docker rm existing container"
}

$startCommand = "touch /opt/app/dcc/perf-reports/app.log && nohup java -XX:StartFlightRecording=name=$jfrName,filename=/opt/app/dcc/perf-reports/$jfrName.jfr,settings=profile,dumponexit=true -jar /opt/app/dcc/app.jar >>/opt/app/dcc/perf-reports/app.log 2>&1 & tail -F /opt/app/dcc/perf-reports/app.log"

docker run -d `
  --name $AppContainer `
  --entrypoint sh `
  --cpus 4 `
  --memory 8g `
  -p "${HostPort}:8080" `
  -v "${datasetPath}:${DatasetContainerPath}:ro" `
  -v "${appJarPath}:/opt/app/dcc/app.jar:ro" `
  -v "${outputDir}:/opt/app/dcc/output" `
  -v "${reportDir}:/opt/app/dcc/perf-reports" `
  -e SERVER_PORT=8080 `
  -e DCC_DATASET_PATH=$DatasetContainerPath `
  -e DCC_OUTPUT_DIR=/opt/app/dcc/output `
  -e DCC_EXPECTED_ROWS=300000 `
  -e DCC_CALLBACK_URL= `
  -e DCC_OUTPUT_BUFFER_BYTES=$OutputBufferBytes `
  -e DCC_ASYNC_WRITE_QUEUE_SLOTS=$AsyncWriteQueueSlots `
  -e DCC_ASYNC_WRITE_WORKER_THREADS=$AsyncWriteWorkerThreads `
  $ImageName `
  -lc $startCommand | Out-Null
Assert-LastExitCode "docker run"

 $healthy = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-WebRequest -Uri "http://127.0.0.1:$HostPort/health" -UseBasicParsing | Out-Null
        $healthy = $true
        break
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $healthy) {
    throw "service did not become healthy on port $HostPort"
}

docker exec $AppContainer sh -lc "rm -f /opt/app/dcc/output/*.csv"
Assert-LastExitCode "cleanup old output files"

$perfStart = Get-Date
if ($InContainerLoadTest) {
    docker cp .\scripts\docker_inapp_load_test.sh "$AppContainer`:/tmp/docker_inapp_load_test.sh"
    Assert-LastExitCode "copy in-container load test script"
    docker exec $AppContainer sh -lc "chmod +x /tmp/docker_inapp_load_test.sh && REQUESTS=$Requests PREFIX=EXTREME sh /tmp/docker_inapp_load_test.sh" | Out-Null
    Assert-LastExitCode "in-container load test"
} else {
    python .\scripts\load_test.py `
      --base-url "http://127.0.0.1:$HostPort" `
      --requests $Requests `
      --concurrency $Concurrency `
      --output-dir $outputDir `
      --expected-files $Requests `
      --request-prefix EXTREME
    Assert-LastExitCode "python load test"
}

$deadline = (Get-Date).AddMinutes(15)
$stable = 0
$lastBytes = -1L
do {
    $files = Get-ChildItem $outputDir -Filter 'EXTREME_*.csv' -ErrorAction SilentlyContinue
    $count = @($files).Count
    $bytes = ($files | Measure-Object Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    if ($count -eq $Requests -and $bytes -gt 0 -and $bytes -eq $lastBytes) {
        $stable++
    } else {
        $stable = 0
        $lastBytes = $bytes
    }
    Write-Output ("progress count={0} totalMB={1:N2} stable={2} poll={3}s" -f $count, ($bytes / 1MB), $stable, $PollIntervalSeconds)
    if ($stable -ge 3) { break }
    Start-Sleep -Seconds $PollIntervalSeconds
} while ((Get-Date) -lt $deadline)
if ($stable -lt 3) {
    throw "output files did not stabilize within 15 minutes"
}
$perfEnd = Get-Date
$elapsedSeconds = [Math]::Round(($perfEnd - $perfStart).TotalSeconds, 2)
$requestsPerSecond = if ($elapsedSeconds -gt 0) { [Math]::Round($Requests / $elapsedSeconds, 2) } else { 0 }
$throughputMBps = if ($elapsedSeconds -gt 0) { [Math]::Round(($bytes / 1MB) / $elapsedSeconds, 2) } else { 0 }

$jcmdList = docker exec $AppContainer jcmd
Assert-LastExitCode "list Java processes"
$javaLine = $jcmdList | Where-Object { $_ -match 'app\.jar' } | Select-Object -First 1
if ($null -eq $javaLine) {
    throw "failed to locate application JVM in jcmd output"
}
$javaPid = $javaLine.ToString().Split()[0].Trim()
if ([string]::IsNullOrWhiteSpace($javaPid)) {
    throw "failed to locate Java PID inside container"
}
docker exec $AppContainer jcmd $javaPid JFR.dump name=$jfrName filename=/opt/app/dcc/perf-reports/$jfrName.jfr | Out-Null
Assert-LastExitCode "JFR dump"
docker exec $AppContainer jfr summary /opt/app/dcc/perf-reports/$jfrName.jfr | Set-Content -Path (Join-Path $reportDir "$jfrName.summary.txt")
Assert-LastExitCode "JFR summary"
docker exec $AppContainer jfr print --events jdk.CPULoad,jdk.GarbageCollection,jdk.ThreadPark,jdk.ThreadAllocationStatistics,jdk.ExecutionSample /opt/app/dcc/perf-reports/$jfrName.jfr | Set-Content -Path (Join-Path $reportDir "$jfrName.events.txt")
Assert-LastExitCode "JFR events"
$summaryFile = Join-Path $reportDir "$jfrName.run.txt"
@(
    "runId=$runId"
    "container=$AppContainer"
    "hostPort=$HostPort"
    "dataset=$datasetPath"
    "requests=$Requests"
    "concurrency=$Concurrency"
    "fields=$FieldsJson"
    "outputDir=$outputDir"
    "reportDir=$reportDir"
    "perfStart=$($perfStart.ToString("s"))"
    "perfEnd=$($perfEnd.ToString("s"))"
    "elapsedSeconds=$elapsedSeconds"
    "totalCsvFiles=$count"
    "totalCsvBytes=$bytes"
    "requestsPerSecond=$requestsPerSecond"
    "throughputMBps=$throughputMBps"
    "pollIntervalSeconds=$PollIntervalSeconds"
    "outputBufferBytes=$OutputBufferBytes"
    "asyncWriteQueueSlots=$AsyncWriteQueueSlots"
    "asyncWriteWorkerThreads=$AsyncWriteWorkerThreads"
) | Set-Content -Path $summaryFile
docker exec $AppContainer sh -lc "rm -f /opt/app/dcc/output/*.csv" | Out-Null
Assert-LastExitCode "cleanup output files"
docker stop $AppContainer | Out-Null
Assert-LastExitCode "docker stop"

Write-Output "JFR: $reportDir\$jfrName.jfr"
Write-Output "Summary: $reportDir\$jfrName.summary.txt"
Write-Output "Events: $reportDir\$jfrName.events.txt"
Write-Output "Run summary: $summaryFile"
Write-Output "App log: $reportDir\app.log"
Write-Output "Elapsed seconds: $elapsedSeconds"
Write-Output "Requests/sec: $requestsPerSecond"
Write-Output "Throughput MB/s: $throughputMBps"
Write-Output "Output CSV files cleaned: $outputDir"
