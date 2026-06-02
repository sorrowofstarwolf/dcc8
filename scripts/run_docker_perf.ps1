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
    [string]$FieldsJson = '["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]',
    [switch]$InContainerLoadTest
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
$runId = Get-Date -Format "yyyyMMddHHmmss"
$outputDirRoot = Join-Path $root $OutputDirHost.TrimStart(".\")
$outputDir = Join-Path $outputDirRoot ("run-" + $runId)
$reportDir = Join-Path $root $ReportDirHost.TrimStart(".\")
$jfrName = "perf-" + $runId

New-Item -ItemType Directory -Force -Path $outputDirRoot | Out-Null
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

mvn -q -DskipTests package
Assert-LastExitCode "mvn package"
docker build --pull=false -t $ImageName .
Assert-LastExitCode "docker build"
$existingContainer = docker ps -aq -f "name=^${AppContainer}$"
if ($existingContainer) {
    docker rm -f $AppContainer | Out-Null
    Assert-LastExitCode "docker rm existing container"
}

$startCommand = "nohup java -XX:StartFlightRecording=name=$jfrName,filename=/opt/app/dcc/perf-reports/$jfrName.jfr,settings=profile,dumponexit=true -jar /opt/app/dcc/app.jar >/opt/app/dcc/perf-reports/app.log 2>&1 & tail -f /opt/app/dcc/perf-reports/app.log"

docker run -d `
  --name $AppContainer `
  --entrypoint sh `
  --cpus 4 `
  --memory 8g `
  -p "${HostPort}:8080" `
  -v "${datasetPath}:${DatasetContainerPath}:ro" `
  -v "${outputDir}:/opt/app/dcc/output" `
  -v "${reportDir}:/opt/app/dcc/perf-reports" `
  -e SERVER_PORT=8080 `
  -e DCC_DATASET_PATH=$DatasetContainerPath `
  -e DCC_OUTPUT_DIR=/opt/app/dcc/output `
  -e DCC_EXPECTED_ROWS=300000 `
  -e DCC_CALLBACK_URL= `
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
    Write-Output ("progress count={0} totalMB={1:N2} stable={2}" -f $count, ($bytes / 1MB), $stable)
    if ($stable -ge 3) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)
if ($stable -lt 3) {
    throw "output files did not stabilize within 15 minutes"
}

$javaPid = docker exec $AppContainer sh -lc "ps -ef | awk '/[j]ava -XX:StartFlightRecording/ {print \$2; exit}'"
Assert-LastExitCode "find Java PID"
$javaPid = $javaPid.Trim()
if ([string]::IsNullOrWhiteSpace($javaPid)) {
    throw "failed to locate Java PID inside container"
}
docker exec $AppContainer sh -lc "jcmd $javaPid JFR.dump name=$jfrName filename=/opt/app/dcc/perf-reports/$jfrName.jfr" | Out-Null
Assert-LastExitCode "JFR dump"
docker exec $AppContainer sh -lc "jfr summary /opt/app/dcc/perf-reports/$jfrName.jfr > /opt/app/dcc/perf-reports/$jfrName.summary.txt" | Out-Null
Assert-LastExitCode "JFR summary"
docker exec $AppContainer sh -lc "jfr print --events jdk.CPULoad,jdk.GarbageCollection,jdk.ThreadAllocationStatistics,jdk.ExecutionSample /opt/app/dcc/perf-reports/$jfrName.jfr > /opt/app/dcc/perf-reports/$jfrName.events.txt" | Out-Null
Assert-LastExitCode "JFR events"
docker exec $AppContainer sh -lc "rm -f /opt/app/dcc/output/*.csv" | Out-Null
Assert-LastExitCode "cleanup output files"
docker stop $AppContainer | Out-Null
Assert-LastExitCode "docker stop"

Write-Output "JFR: $reportDir\$jfrName.jfr"
Write-Output "Summary: $reportDir\$jfrName.summary.txt"
Write-Output "Events: $reportDir\$jfrName.events.txt"
Write-Output "App log: $reportDir\app.log"
Write-Output "Output CSV files cleaned: $outputDir"
