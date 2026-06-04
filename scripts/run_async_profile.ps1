param(
    [ValidateSet("cpu", "ctimer", "wall")]
    [string]$Event,
    [string]$ContainerName = "dcc-aprof",
    [int]$HostPort = 18081,
    [int]$ProfileSeconds = 45,
    [string]$IncludePattern = ""
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

function Assert-LastExitCode {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$dataset = (Resolve-Path ".\perf_data_extreme_unique_300000.csv").Path
$jar = (Resolve-Path ".\target\dcc-1.0-SNAPSHOT.jar").Path
$reports = (Resolve-Path ".\perf-reports").Path
$aprof = (Resolve-Path ".\tools\async-profiler-3.0-linux-x64").Path
$outputDir = Join-Path $root "perf-output\run-aprof-$Event"
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

$runId = "aprof-$Event-" + (Get-Date -Format "yyyyMMddHHmmss")
$appLog = "/opt/app/dcc/perf-reports/$runId.app.log"
$profileFile = "/opt/app/dcc/perf-reports/$runId.collapsed"
$startCommand = "touch $appLog && nohup java -jar /opt/app/dcc/app.jar >>$appLog 2>&1 & tail -F $appLog"

$existing = docker ps -aq -f "name=^${ContainerName}$"
if ($existing) {
    docker rm -f $ContainerName | Out-Null
    Assert-LastExitCode "docker rm existing profiler container"
}

docker run -d `
  --name $ContainerName `
  --entrypoint sh `
  --cpus 4 `
  --memory 8g `
  -p "${HostPort}:8080" `
  -v "${dataset}:/opt/app/dcc/perf_data_extreme_unique_300000.csv:ro" `
  -v "${jar}:/opt/app/dcc/app.jar:ro" `
  -v "${outputDir}:/opt/app/dcc/output" `
  -v "${reports}:/opt/app/dcc/perf-reports" `
  -v "${aprof}:/opt/aprof:ro" `
  -e SERVER_PORT=8080 `
  -e DCC_DATASET_PATH=/opt/app/dcc/perf_data_extreme_unique_300000.csv `
  -e DCC_OUTPUT_DIR=/opt/app/dcc/output `
  -e DCC_EXPECTED_ROWS=300000 `
  -e DCC_CALLBACK_URL= `
  dcc:local `
  -lc $startCommand | Out-Null
Assert-LastExitCode "docker run profiler container"

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

docker exec $ContainerName sh -lc "rm -f /opt/app/dcc/output/*.csv"
Assert-LastExitCode "cleanup old output files"

$jcmdOutput = docker exec $ContainerName jcmd
Assert-LastExitCode "list Java processes"
$javaLine = $jcmdOutput | Where-Object { $_ -match 'app\.jar' } | Select-Object -First 1
if ($null -eq $javaLine) {
    throw "failed to locate application JVM in jcmd output"
}
$javaPid = $javaLine.ToString().Split()[0].Trim()

$profileArgs = @("-d", $ProfileSeconds, "-e", $Event, "-t", "-o", "collapsed", "-f", $profileFile)
if (-not [string]::IsNullOrWhiteSpace($IncludePattern)) {
    $profileArgs += @("-I", $IncludePattern)
}
$profileArgs += $javaPid
docker exec $ContainerName /opt/aprof/bin/asprof @profileArgs
Assert-LastExitCode "start async-profiler timed run"

docker cp .\scripts\docker_inapp_load_test.sh "$ContainerName`:/tmp/docker_inapp_load_test.sh"
Assert-LastExitCode "copy in-container load script"
docker exec $ContainerName sh -lc "chmod +x /tmp/docker_inapp_load_test.sh && REQUESTS=100 PREFIX=EXTREME sh /tmp/docker_inapp_load_test.sh" | Out-Null
Assert-LastExitCode "run in-container load test"

$deadline = (Get-Date).AddMinutes(15)
$stable = 0
$lastBytes = -1L
do {
    $files = Get-ChildItem $outputDir -Filter 'EXTREME_*.csv' -ErrorAction SilentlyContinue
    $count = @($files).Count
    $bytes = ($files | Measure-Object Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    if ($count -eq 100 -and $bytes -gt 0 -and $bytes -eq $lastBytes) {
        $stable++
    } else {
        $stable = 0
        $lastBytes = $bytes
    }
    if ($stable -ge 3) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($stable -lt 3) {
    throw "output files did not stabilize within 15 minutes"
}

$profileDeadline = (Get-Date).AddMinutes(2)
while ((-not (Test-Path (Join-Path $reports "$runId.collapsed"))) -and ((Get-Date) -lt $profileDeadline)) {
    Start-Sleep -Seconds 2
}
if (-not (Test-Path (Join-Path $reports "$runId.collapsed"))) {
    throw "async-profiler output file was not created"
}

docker exec $ContainerName sh -lc "rm -f /opt/app/dcc/output/*.csv" | Out-Null
Assert-LastExitCode "cleanup output files"
docker stop $ContainerName | Out-Null
Assert-LastExitCode "stop profiler container"

Write-Output "Profile: $reports\$runId.collapsed"
Write-Output "App log: $reports\$runId.app.log"
Write-Output "Output CSV files cleaned: $outputDir"
