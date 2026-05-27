# 4C8G Competition Parameter Experiments

This document records the parameter sets worth testing on the 4C8G competition host.
All experiments should use `perf_data_extreme_unique_300000.csv`, 100 concurrent requests,
random SM4 keys, and the 7 SM4 fields used by `scripts/load_test.py`.

## Current Best Local Baseline

Local best result after the chunk buffer pool optimization:

| Scenario | completeSeconds | Throughput |
| --- | ---: | ---: |
| worker=3, active=3, writePermits=2, chunkWorker=32, chunkRows=10000 | 8.389s | 1006.03 MB/s |
| same code, later rerun | 8.850s | 953.67 MB/s |
| 4C simulated with `-XX:ActiveProcessorCount=4`, chunkWorker=8 | 9.359s | 901.79 MB/s |

The 4C simulated result is the closest local reference for the competition machine, but the
real host disk and CPU scheduling may differ. Run the matrix below on the target host.

## JVM Parameters

Use this JVM baseline first:

```text
-Xms6g -Xmx6g
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m
-XX:MaxGCPauseMillis=100
-XX:+ParallelRefProcEnabled
```

For local 4C simulation only, prepend:

```text
-XX:ActiveProcessorCount=4
```

Do not use `-XX:ActiveProcessorCount=4` on the real 4C competition host unless you need to
force Java to ignore extra visible CPUs.

## Recommended First Run

Start with this set on the real competition machine:

```text
--dcc.worker-threads=3
--dcc.active-heavy-jobs=3
--dcc.write-permits=2
--dcc.chunked-pipeline-enabled=true
--dcc.chunk-worker-threads=8
--dcc.chunk-rows=10000
--dcc.chunk-max-in-flight-per-request=0
--dcc.chunk-buffer-pool-bytes=268435456
```

This is the most promising 4C-oriented configuration from local simulation.

## Experiment Matrix

Run these in order. Stop early if one configuration is clearly unstable or slower by more
than 10%.

| Group | worker | active-heavy-jobs | write-permits | chunk-worker | chunk-rows | max-in-flight | Purpose |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| A1 | 3 | 3 | 2 | 8 | 10000 | 0 | 4C baseline |
| A2 | 3 | 3 | 2 | 10 | 10000 | 0 | More chunk parallelism |
| A3 | 3 | 3 | 2 | 12 | 10000 | 0 | Upper chunk parallelism check |
| B1 | 2 | 2 | 2 | 8 | 10000 | 0 | Less request-level contention |
| B2 | 2 | 2 | 2 | 10 | 10000 | 0 | Lower active jobs, more chunk workers |
| C1 | 3 | 2 | 2 | 8 | 10000 | 0 | Limit heavy requests, keep 3 HTTP workers |
| C2 | 3 | 2 | 2 | 10 | 10000 | 0 | Same with more chunk workers |
| D1 | 3 | 3 | 1 | 8 | 10000 | 0 | Single writer permit |
| D2 | 3 | 3 | 3 | 8 | 10000 | 0 | More writer concurrency |
| E1 | 3 | 3 | 2 | 8 | 15000 | 0 | Larger chunks, fewer tasks |
| E2 | 3 | 3 | 2 | 8 | 20000 | 0 | Even fewer chunk tasks |
| F1 | 3 | 3 | 2 | 8 | 10000 | 6 | Lower memory pressure guard |
| F2 | 3 | 3 | 2 | 10 | 10000 | 6 | Guarded in-flight with more workers |

## Success Criteria

Prefer the configuration with the best stable `completeSeconds`, not only the best single
run. For the final choice:

- Run the top 2 configurations at least 3 times.
- Use the same dataset and request pattern for all runs.
- Confirm `success=100` and `failures=0`.
- Confirm `outputMB` is about `8440 MB`.
- Watch for GC pauses or disk jitter if JFR is enabled.

## Example Command

```powershell
python .\scripts\load_test.py `
  --jar target\dcc-1.0-SNAPSHOT.jar `
  --port 18189 `
  --dataset-path perf_data_extreme_unique_300000.csv `
  --worker-threads 3 `
  --active-heavy-jobs 3 `
  --write-permits 2 `
  --chunked-pipeline-enabled `
  --chunk-worker-threads 8 `
  --chunk-rows 10000 `
  --concurrency 100 `
  --timeout-seconds 240 `
  --startup-timeout-seconds 90 `
  --wait-seconds 600 `
  --stable-seconds 3 `
  --min-output-mb 8000 `
  --output-dir load-output-4c-test `
  --report-dir load-test-reports\4c-test `
  --jvm-args "-Xms6g -Xmx6g -XX:+UseG1GC -XX:G1HeapRegionSize=16m -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled"
```

Delete the `load-output-*` directory after each run to avoid filling the disk.

## Notes

- `chunk-buffer-pool-bytes=268435456` was a major local win. Keep it enabled unless the
  competition host shows memory pressure.
- `chunk-max-in-flight-per-request=6` is a safety valve. It was slightly slower locally,
  but can help if the 4C8G host has GC or memory spikes.
- Experiments that were tried and rejected locally: replacing `ExecutorCompletionService`,
  reusing `FixedCipherCache[]`, and reusing tiny per-chunk cell buffers.
