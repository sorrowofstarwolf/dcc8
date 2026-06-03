# AGENTS.md

## Project Overview

This repository is a Java 11 / Spring Boot 2.7 Maven service for a dynamic data masking and encryption contest. The service exposes:

- `GET /health`: returns service readiness without triggering encryption-related initialization.
- `POST /encrypt`: loads the CSV dataset lazily, writes encrypted/masked CSV output, and optionally calls back to the verifier.

The core objective is byte-for-byte correctness with high throughput under a 4 CPU / 8 GB style workload and roughly 100 concurrent encryption requests.

## Hard Constraints

- Do not read the input dataset, allocate large encryption data structures, or initialize encryption business logic before `/health` has been called. The current design intentionally loads data lazily on the first `/encrypt`.
- Preserve CSV output semantics:
  - UTF-8, no BOM.
  - No header row.
  - No quoting around values.
  - Output columns follow the exact order of `fieldsToEncrypt`.
  - Each output row corresponds to the same input row number.
  - Close/finish the output file before callback.
- SM4 fields are encrypted per request key. Mask fields are key-independent and may be precomputed after the first `/encrypt` load.
- Keep memory predictable. Existing data structures use fixed pools, column-oriented storage, direct buffers, and fixed-capacity caches to avoid object churn and uncontrolled growth.
- Treat performance changes carefully. Small allocations inside row/cell loops can dominate runtime at 100 concurrent requests.

## Important Files

- `pom.xml`: Maven build, Java 11, Spring Boot 2.7.18, BouncyCastle.
- `src/main/java/com/dcc/DccApplication.java`: application entry point.
- `src/main/java/com/dcc/web/HealthController.java`: health endpoint.
- `src/main/java/com/dcc/web/EncryptController.java`: encrypt endpoint.
- `src/main/java/com/dcc/service/EncryptService.java`: request scheduling, encryption, output writing, callback.
- `src/main/java/com/dcc/store/DataStore.java`: lazy CSV loading and columnar in-memory storage.
- `src/main/java/com/dcc/domain/FieldId.java`: field mapping and field categories.
- `src/main/java/com/dcc/crypto/Sm4Cipher.java`: SM4/CBC/PKCS5Padding implementation wrapper.
- `src/main/java/com/dcc/config/AppProperties.java`: `dcc.*` configuration binding.
- `src/main/resources/application.yml`: default runtime configuration.
- `src/test/java`: correctness, crypto, store, masking, and concurrency tests.
- `scripts/`: local Docker/performance/profiling helpers.
- `docs/`: algorithm, Docker, and performance notes.

Generated runtime outputs such as `target/`, `perf-output/`, `perf-reports/`, `local-perf-output/`, and `local-perf-reports/` should generally remain untracked.

## Common Commands

Run tests:

```powershell
mvn test
```

Build the runnable jar:

```powershell
mvn package
```

Run locally after packaging:

```powershell
java -jar target\dcc-1.0-SNAPSHOT.jar
```

Health check:

```powershell
Invoke-WebRequest http://127.0.0.1:8080/health -UseBasicParsing
```

Docker 4C8G-style run:

```powershell
docker compose build
docker compose up -d
Invoke-WebRequest http://127.0.0.1:8080/health -UseBasicParsing
docker compose down
```

Contest-like Docker performance flow:

```powershell
.\scripts\run_docker_perf.ps1 -InContainerLoadTest
```

The standard Docker performance run uses:

- `100` concurrent requests
- `100` total requests
- dataset `perf_data_extreme_unique_300000.csv`
- request prefix `EXTREME`
- fields `["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]`
- output polling interval `1` second
- output stability rule: `3` consecutive polls with `100` files and unchanged total size

## Configuration Notes

Main `dcc.*` properties include:

- `team-code`
- `dataset-path`
- `output-dir`
- `callback-url`
- `worker-threads`
- `queue-capacity`
- `expected-rows`
- `initial-raw-pool-bytes`
- `initial-mask-pool-bytes`
- `output-buffer-bytes`
- `async-write-queue-slots`
- `async-write-worker-threads`
- `cache-capacity`
- `request-timeout-millis`
- `baseline-validation-enabled`

When changing configuration defaults, make sure the Docker scripts and tests still line up with the expected dataset paths and output directories.

## Development Guidance

- Prefer the existing low-allocation style in hot paths. Avoid `String`, `List`, `HashMap`, stream APIs, and general CSV libraries inside per-row/per-cell loops unless the performance impact has been measured.
- Keep `/health` simple and side-effect-free.
- Keep `fieldsToEncrypt` validation strict. Unknown field names should fail fast.
- Preserve `FieldId` ordering and field categories unless the input CSV contract changes.
- For masking, handle characters rather than raw UTF-8 bytes so Chinese names are not split incorrectly.
- For SM4, preserve `SM4/CBC/PKCS5Padding`, fixed IV `1234567890123456`, BouncyCastle provider behavior, and uppercase hex output.
- Be careful with concurrency: request workers, async writer threads, queue capacities, and direct buffer sizes are tuned together.
- If changing output line endings or buffering, rerun baseline validation and inspect generated CSV bytes.
- Do not commit generated profiler bundles, large datasets, JFR files, app logs, or output CSVs unless explicitly requested.

## Verification Expectations

For narrow changes, at minimum run:

```powershell
mvn test
```

For changes touching encryption, masking, CSV loading, field ordering, output writing, or callbacks, also run:

```powershell
mvn package
```

For performance-sensitive changes, run the Docker performance script and compare:

- total completion time,
- generated output count,
- output size stability,
- JFR summary/events where relevant,
- application logs for load and request timings.

## Standard Docker Performance Flow

Every Docker-based performance run must produce JFR artifacts and retain only diagnostic metrics, not generated encryption CSV files. The purpose of each run is to compare the program's CPU, GC, blocking, and worker utilization characteristics under a repeatable 4 CPU / 8 GB style environment.

The repository standard is a fixed Docker pressure test using `100` concurrent requests against `perf_data_extreme_unique_300000.csv`, with `7` SM4 fields plus `1` mask field:

- SM4 fields: `user_id`, `serial_no`, `user_code`, `business_key`, `device_id`, `trans_id`, `secret_code`
- Mask field: `name`
- Request prefix: `EXTREME`

Required retained artifacts:

- JFR recording: `perf-reports/*.jfr`
- JFR summary: `perf-reports/*.summary.txt`
- JFR selected events: `perf-reports/*.events.txt`
- Application log for load/request timing: `perf-reports/app.log`
- Any concise run summary that records dataset, request count, fields, thread settings, completion time, and output size before cleanup.

Do not retain generated encrypted CSV output after the run. CSV files are only temporary correctness/performance byproducts and should be removed from container and host output directories once their count and size stability have been recorded.

Each JFR review should capture at least:

- GC frequency, pause time, allocation pressure, and heap behavior.
- `ThreadPark` / blocking events that show queue waits, callback waits, file-write waits, or thread-pool contention.
- CPU usage of encryption workers, async file writer threads, and HTTP request threads.
- Hot methods from execution samples, especially SM4 encryption, CSV row generation, cache lookup, direct buffer writes, and file I/O.
- Whether configured worker counts oversubscribe or underutilize the available 4 CPU budget.

Use this checklist for every Docker performance run:

1. Confirm the worktree state with `git status --short` and note any local changes that may affect performance.
2. Confirm Docker is available and no previous `dcc-perf-app` container is running.
3. Clean prior temporary CSV outputs from `perf-output/`, `local-perf-output/`, and container output directories before starting.
4. Build/package from the current source so the image and jar match the code under test.
5. Start the container with the intended 4 CPU / 8 GB resource limits and JFR enabled from JVM startup.
6. Wait for `/health` to return `{"status":"SUCCESS"}` before sending encryption traffic.
7. Run the fixed workload: `100` concurrent requests, `100` total requests, dataset `perf_data_extreme_unique_300000.csv`, fields `["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]`, and request prefix `EXTREME`.
8. Observe output CSV count and total size every `1` second until `100` files exist and total size is unchanged for `3` consecutive polls.
9. Dump or close the JFR recording, generate JFR summary/events text, and preserve the application log.
10. Delete generated CSV outputs from host and container locations.
11. Stop the container cleanly.
12. Compare only retained metrics and JFR evidence across runs; do not compare leftover CSV artifacts.

When a run fails, record the exact failing step before retrying. Avoid mixing environment fixes with code changes in the same performance result; rerun the full checklist after any Docker, JVM, script, dataset, or configuration adjustment.

## Git Hygiene

- The worktree may contain user or generated changes. Do not revert unrelated modifications.
- Keep edits scoped to the requested behavior.
- Before committing, review `git status --short` and avoid staging generated artifacts.
