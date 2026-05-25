# Fourth Optimization: Controlled Output Workflow

## Goal

This branch optimizes the request execution workflow around the two bottlenecks observed in profiling and load tests:

- large per-request output buffers competing for heap, memory bandwidth, and GC;
- concurrent large file writes competing for the filesystem and disk cache.

It also keeps the earlier asynchronous callback model so encryption workers are not blocked by callback network I/O.

The SM4 semantics remain unchanged:

- each request uses its own `sm4Key`;
- each cell is encrypted independently with SM4-CBC and fixed IV `1234567890123456`;
- padding remains compatible with `SM4/CBC/PKCS5Padding`;
- ciphertext is emitted as uppercase HEX;
- mask fields are emitted from precomputed masked column data.

## Main Changes

### 1. Adaptive Output Mode

`EncryptService` now estimates the exact CSV output size before writing a request result.

```text
outputLength <= dcc.max-buffered-output-bytes
  -> buffered mode

outputLength > dcc.max-buffered-output-bytes
  -> streaming mode
```

Buffered mode builds the full CSV file in one `byte[]` and writes it once. This is useful for small outputs where reducing writer calls is more important than heap pressure.

Streaming mode writes through `FastCsvWriter` with a fixed buffer. This avoids holding a full output file in heap, which is important for extreme requests where each output file is about 84 MB.

Current default:

```yaml
dcc:
  max-buffered-output-bytes: 33554432
```

Load tests also showed that `10485760` can be a good candidate because it forces the normal 22.9 MB output files through streaming mode.

### 2. Write-Permit Throttling

File writes are wrapped by a permit-controlled `OutputStream`.

```yaml
dcc:
  write-permits: 1
```

This allows encryption workers to continue running concurrently while limiting the number of threads that enter actual `OutputStream.write(...)` / `flush()` calls at the same time.

The intent is to avoid several workers pushing large CSV files into the filesystem simultaneously. This reduces disk queue contention and Windows file cache pressure.

### 3. Asynchronous Callback Pool

Callbacks use a dedicated executor:

```yaml
dcc:
  callback-threads: 4
  callback-queue-capacity: 128
```

The encryption worker writes and closes the output file, then submits the callback task and returns to the encryption queue. Callback failures are logged without killing the callback worker.

This preserves the required ordering:

```text
write complete -> callback submitted
```

while avoiding this slower synchronous worker cycle:

```text
encrypt -> write -> wait for callback response -> next request
```

### 4. SM4 Block Round Unroll

`Sm4Cipher.Context.encryptBlock(...)` now fully expands the 32 SM4 rounds and loads the round keys into local variables.

The previous implementation used a loop:

```text
for i = 0; i < 32; i += 4
```

The current implementation removes loop control overhead and repeated indexed round-key lookups in the hot block-encryption path.

Correctness was checked against BouncyCastle for plaintext lengths 0 through 64 bytes.

### 5. Rejected Length-17/18 Specialization

Dedicated `encryptLength17` / `encryptLength18` paths were tested for fields such as `device_id` and `trans_id`.

They were correct, but performance regressed in local extreme tests. The likely reason is that these fields still require two SM4 blocks, so the saved padding logic did not offset the extra branching and JIT/code-layout cost.

Those changes were reverted and are not part of the final design.

## Current Execution Flow

```text
HTTP /encrypt request
  -> validate and map field names
  -> enqueue EncryptTask

EncryptTask
  -> load shared columnar data
  -> create request SM4 context
  -> encrypt request dictionaries when applicable
  -> estimate output length
  -> choose buffered or streaming output
  -> write through write permits
  -> submit callback task

Callback executor
  -> POST callback request
```

## Why This Helps

The previous full-buffer output strategy was fast for small files but harmful for large concurrent outputs.

Extreme 7-SM4 output size:

```text
one request ~= 84.4 MB
3 workers   ~= 253 MB of output arrays alive at once
```

That competes for:

- heap and large-object allocation;
- memory bandwidth;
- CPU cache;
- filesystem writeback and disk queue capacity.

The adaptive workflow keeps the buffered strategy where it helps, but switches large files to streaming mode before they create this pressure.

## Local Benchmark Snapshot

Environment:

```text
JVM flag: -XX:ActiveProcessorCount=4
workerThreads: 3
callbackUrl: empty
requests: 100
rows per request: 300000
```

### Normal request fields

Fields:

```text
phone,user_code,user_id,name
```

Recent measurements:

| Variant | Complete time | Throughput |
|---|---:|---:|
| Previous buffered baseline | 3.64 s | 629.49 MB/s |
| Adaptive/write-permit only | 3.02 s | 758.39 MB/s |
| Adaptive/write-permit + SM4 unroll | 2.71 s | 844.89 MB/s |
| Forced streaming, 10 MB threshold | 2.13 s | 1075.57 MB/s |
| Forced streaming, 0 MB threshold | 2.38 s | 962.09 MB/s |

### Extreme 7-SM4 fields

Fields:

```text
user_id,serial_no,user_code,business_key,device_id,trans_id,secret_code
```

Recent measurements:

| Variant | Complete time | Throughput |
|---|---:|---:|
| Previous buffered baseline | 24.87 s | 339.35 MB/s |
| Adaptive/write-permit only | 17.51 s | 481.96 MB/s |
| Adaptive/write-permit + SM4 unroll | 14.40 s | 586.32 MB/s |
| Forced streaming, 0 MB threshold | 14.92 s | 565.65 MB/s |

The exact timings depend on machine load and disk state, but the trend is consistent:

- large files should avoid full output arrays;
- write concurrency should be controlled;
- SM4 round unrolling improves the CPU-heavy path.

## Tuning Guidance

Recommended starting point:

```yaml
dcc:
  worker-threads: 3
  write-permits: 1
  callback-threads: 4
  callback-queue-capacity: 128
  output-buffer-bytes: 1048576
  max-buffered-output-bytes: 10485760
```

For a fast disk and larger heap, test these thresholds:

```text
0 MB      all streaming
10 MB     normal and extreme streaming
32 MB     normal buffered, extreme streaming
64 MB     normal buffered, extreme streaming
96 MB     normal and extreme buffered
```

In local tests, `10 MB` was the best normal-field threshold, while extreme-field behavior was close between `10 MB`, `32 MB`, and all-streaming because all of them choose streaming for the 84 MB output.

## Correctness Checks

The following checks were run after the workflow changes:

```text
mvn -DskipTests package
VERIFY_SM4_BC_OK lengths=0..64
VERIFY_ALL_FIELDS_BC_OK fields=11
VERIFY_QUOTED_CSV_BC_OK
```

`mvn test` still has a local baseline fixture mismatch related to line endings / masked placeholder data in `BaselineValidationTest`; the SM4 and generated-field validation checks pass.
