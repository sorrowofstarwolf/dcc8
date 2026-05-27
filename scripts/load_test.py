#!/usr/bin/env python3
import argparse
import concurrent.futures
import json
import os
import shutil
import signal
import shlex
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_FIELDS = [
    "user_id",
    "serial_no",
    "user_code",
    "business_key",
    "device_id",
    "trans_id",
    "secret_code",
]
DEFAULT_DATASET_PATH = "perf_data_extreme_unique_300000.csv"
KEY_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def parse_args():
    parser = argparse.ArgumentParser(description="HTTP load test for the DCC /encrypt endpoint.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="Service base URL.")
    parser.add_argument("--requests", type=int, default=100, help="Total request count.")
    parser.add_argument("--concurrency", type=int, default=100, help="Concurrent worker count.")
    parser.add_argument("--timeout-seconds", type=float, default=300, help="Per-request timeout.")
    parser.add_argument("--wait-seconds", type=float, default=600, help="Max wait time for output files.")
    parser.add_argument("--stable-seconds", type=int, default=3, help="Output size must be stable for this many checks.")
    parser.add_argument("--output-dir", default="", help="Directory where CSV files are generated.")
    parser.add_argument("--min-output-mb", type=float, default=0, help="Fail when generated output is smaller than this many MB.")
    parser.add_argument("--fields", default=",".join(DEFAULT_FIELDS), help="Comma separated fieldsToEncrypt.")
    parser.add_argument("--sm4-key", default="2123433411630000", help="SM4 key sent in every request.")
    parser.add_argument(
        "--random-sm4-key",
        dest="random_sm4_key",
        action="store_true",
        default=True,
        help="Generate a random 16-character SM4 key per request. Enabled by default.",
    )
    parser.add_argument(
        "--fixed-sm4-key",
        dest="random_sm4_key",
        action="store_false",
        help="Use --sm4-key for every request instead of random keys.",
    )
    parser.add_argument("--ip", default="55.51.53.74", help="IP sent in every request.")
    parser.add_argument("--request-prefix", default="", help="Request id prefix; default is LOAD_<timestamp>.")
    parser.add_argument("--report-dir", default="", help="Optional directory to write summary.json.")
    parser.add_argument("--service-log-dir", default="", help="Optional directory for service stdout/stderr when --jar is set.")
    parser.add_argument("--jar", default="", help="Optional jar path. When set, the script starts/stops the service.")
    parser.add_argument("--jvm-args", default="", help="Optional JVM arguments used before -jar when --jar is set.")
    parser.add_argument("--jfr-file", default="", help="Optional JFR output file. Requires --jar.")
    parser.add_argument("--jfr-repository", default="jfr-repository", help="JFR repository directory when --jfr-file is set.")
    parser.add_argument("--port", type=int, default=8080, help="Port used when --jar is set.")
    parser.add_argument("--dataset-path", default=DEFAULT_DATASET_PATH, help="dcc.dataset-path when --jar is set.")
    parser.add_argument("--expected-rows", type=int, default=300000, help="dcc.expected-rows when --jar is set.")
    parser.add_argument("--worker-threads", type=int, default=4, help="Optional dcc.worker-threads when --jar is set.")
    parser.add_argument("--active-heavy-jobs", type=int, default=0, help="Optional dcc.active-heavy-jobs when --jar is set.")
    parser.add_argument("--write-permits", type=int, default=0, help="Optional dcc.write-permits when --jar is set.")
    parser.add_argument("--chunked-pipeline-enabled", action="store_true", help="Enable dcc.chunked-pipeline-enabled when --jar is set.")
    parser.add_argument("--chunk-worker-threads", type=int, default=0, help="Optional dcc.chunk-worker-threads when --jar is set.")
    parser.add_argument("--chunk-rows", type=int, default=0, help="Optional dcc.chunk-rows when --jar is set.")
    parser.add_argument("--chunk-max-in-flight-per-request", type=int, default=0, help="Optional dcc.chunk-max-in-flight-per-request when --jar is set.")
    parser.add_argument("--chunk-buffer-pool-bytes", type=int, default=-1, help="Optional dcc.chunk-buffer-pool-bytes when --jar is set.")
    parser.add_argument("--startup-timeout-seconds", type=float, default=60, help="Health wait timeout when --jar is set.")
    parser.add_argument("--clean-output", action="store_true", help="Remove output dir before the run.")
    return parser.parse_args()


def post_json(url, payload, timeout_seconds):
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        body = response.read().decode("utf-8", errors="replace")
        return {
            "status": response.status,
            "body": body,
            "elapsed_ms": round((time.perf_counter() - started) * 1000, 2),
        }


def get_text(url, timeout_seconds):
    with urllib.request.urlopen(url, timeout=timeout_seconds) as response:
        return response.read().decode("utf-8", errors="replace")


def random_sm4_key():
    return "".join(secrets.choice(KEY_ALPHABET) for _ in range(16))


def wait_for_health(base_url, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            return get_text(base_url.rstrip("/") + "/health", 2)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = exc
            time.sleep(1)
    raise RuntimeError(f"health check failed after {timeout_seconds}s: {last_error}")


def start_service(args, output_dir, log_dir):
    java = shutil.which("java")
    if not java:
        raise RuntimeError("java not found in PATH")

    jvm_args = shlex.split(args.jvm_args)
    if args.jfr_file:
        repository = Path(args.jfr_repository).resolve()
        repository.mkdir(parents=True, exist_ok=True)
        Path(args.jfr_file).resolve().parent.mkdir(parents=True, exist_ok=True)
        jvm_args.extend([
            f"-XX:FlightRecorderOptions=repository={repository}",
            "-XX:StartFlightRecording=name=dcc,settings=profile,maxsize=512m",
        ])

    cmd = [
        java,
        *jvm_args,
        "-jar",
        args.jar,
        f"--server.port={args.port}",
    ]
    if output_dir:
        cmd.append(f"--dcc.output-dir={output_dir}")
    if args.dataset_path:
        cmd.append(f"--dcc.dataset-path={args.dataset_path}")
    if args.expected_rows:
        cmd.append(f"--dcc.expected-rows={args.expected_rows}")
    if args.worker_threads:
        cmd.append(f"--dcc.worker-threads={args.worker_threads}")
    if args.active_heavy_jobs:
        cmd.append(f"--dcc.active-heavy-jobs={args.active_heavy_jobs}")
    if args.write_permits:
        cmd.append(f"--dcc.write-permits={args.write_permits}")
    if args.chunked_pipeline_enabled:
        cmd.append("--dcc.chunked-pipeline-enabled=true")
    if args.chunk_worker_threads:
        cmd.append(f"--dcc.chunk-worker-threads={args.chunk_worker_threads}")
    if args.chunk_rows:
        cmd.append(f"--dcc.chunk-rows={args.chunk_rows}")
    if args.chunk_max_in_flight_per_request:
        cmd.append(f"--dcc.chunk-max-in-flight-per-request={args.chunk_max_in_flight_per_request}")
    if args.chunk_buffer_pool_bytes >= 0:
        cmd.append(f"--dcc.chunk-buffer-pool-bytes={args.chunk_buffer_pool_bytes}")

    stdout = subprocess.DEVNULL
    stderr = subprocess.DEVNULL
    if log_dir:
        Path(log_dir).mkdir(parents=True, exist_ok=True)
        stdout = open(Path(log_dir) / "service.out.log", "wb")
        stderr = open(Path(log_dir) / "service.err.log", "wb")
    process = subprocess.Popen(cmd, stdout=stdout, stderr=stderr)
    process._dcc_log_files = [file for file in (stdout, stderr) if hasattr(file, "close")]
    return process


def dump_jfr(process, jfr_file):
    if not process or process.poll() is not None or not jfr_file:
        return
    target = Path(jfr_file).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        subprocess.run(
            ["jcmd", str(process.pid), "JFR.dump", "name=dcc", f"filename={target}"],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=30,
        )
    except Exception:
        pass


def stop_service(process):
    if not process or process.poll() is not None:
        return
    if os.name == "nt":
        process.terminate()
    else:
        process.send_signal(signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
    for file in getattr(process, "_dcc_log_files", []):
        file.close()


def output_stats(output_dir):
    path = Path(output_dir)
    if not output_dir or not path.exists():
        return {"files": 0, "bytes": 0}
    files = list(path.glob("*.csv"))
    total_bytes = sum(item.stat().st_size for item in files if item.is_file())
    return {"files": len(files), "bytes": total_bytes}


def wait_for_outputs(output_dir, expected_files, wait_seconds, stable_seconds):
    if not output_dir:
        return output_stats(output_dir)

    deadline = time.monotonic() + wait_seconds
    last_bytes = -1
    stable_count = 0
    stats = output_stats(output_dir)
    while time.monotonic() < deadline:
        stats = output_stats(output_dir)
        mb = stats["bytes"] / 1024 / 1024
        print(f"progress files={stats['files']} mb={mb:.2f}", flush=True)

        if stats["files"] >= expected_files and stats["bytes"] > 0 and stats["bytes"] == last_bytes:
            stable_count += 1
            if stable_count >= stable_seconds:
                break
        else:
            stable_count = 0
            last_bytes = stats["bytes"]
        time.sleep(1)
    return stats


def percentile(values, pct):
    if not values:
        return 0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def main():
    args = parse_args()
    base_url = args.base_url.rstrip("/")
    if args.jar:
        base_url = f"http://127.0.0.1:{args.port}"

    fields = [item.strip() for item in args.fields.split(",") if item.strip()]
    run_id = time.strftime("%Y%m%d%H%M%S")
    request_prefix = args.request_prefix or f"LOAD_{run_id}"
    output_dir = args.output_dir
    if not output_dir and args.jar:
        output_dir = f"load-output-{run_id}"
    service_log_dir = args.service_log_dir or args.report_dir

    if output_dir and args.clean_output:
        output_path = Path(output_dir)
        if output_path.exists():
            shutil.rmtree(output_path)

    if output_dir:
        Path(output_dir).mkdir(parents=True, exist_ok=True)

    process = None
    try:
        if args.jar:
            process = start_service(args, output_dir, service_log_dir)
            print(f"started service pid={process.pid} base_url={base_url}", flush=True)

        health = wait_for_health(base_url, args.startup_timeout_seconds if args.jar else 10)
        print(f"health={health}", flush=True)

        request_url = base_url + "/encrypt"
        started = time.perf_counter()
        results = []
        failures = []

        def send(index):
            payload = {
                "requestId": f"{request_prefix}_{index:04d}",
                "sm4Key": random_sm4_key() if args.random_sm4_key else args.sm4_key,
                "ip": args.ip,
                "fieldsToEncrypt": fields,
            }
            return post_json(request_url, payload, args.timeout_seconds)

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            future_to_index = {executor.submit(send, index): index for index in range(args.requests)}
            for future in concurrent.futures.as_completed(future_to_index):
                index = future_to_index[future]
                try:
                    results.append(future.result())
                except Exception as exc:
                    failures.append({"index": index, "error": repr(exc)})

        post_elapsed = time.perf_counter() - started
        stats = wait_for_outputs(output_dir, args.requests, args.wait_seconds, args.stable_seconds)
        complete_elapsed = time.perf_counter() - started

        latencies = [item["elapsed_ms"] for item in results]
        summary = {
            "baseUrl": base_url,
            "requests": args.requests,
            "concurrency": args.concurrency,
            "success": len(results),
            "failures": len(failures),
            "httpPostSeconds": round(post_elapsed, 3),
            "completeSeconds": round(complete_elapsed, 3),
            "latencyMs": {
                "min": min(latencies) if latencies else 0,
                "p50": percentile(latencies, 50),
                "p90": percentile(latencies, 90),
                "p95": percentile(latencies, 95),
                "p99": percentile(latencies, 99),
                "max": max(latencies) if latencies else 0,
            },
            "outputDir": output_dir,
            "outputFiles": stats["files"],
            "outputMB": round(stats["bytes"] / 1024 / 1024, 2),
            "throughputReqPerSec": round(len(results) / post_elapsed, 2) if post_elapsed > 0 else 0,
            "throughputMBPerSec": round((stats["bytes"] / 1024 / 1024) / complete_elapsed, 2)
            if complete_elapsed > 0
            else 0,
        }
        if failures:
            summary["failureSamples"] = failures[:5]
        if stats["files"] < args.requests:
            summary["incompleteOutput"] = {
                "expectedFiles": args.requests,
                "actualFiles": stats["files"],
            }
        output_mb = stats["bytes"] / 1024 / 1024
        if args.min_output_mb and output_mb < args.min_output_mb:
            summary["incompleteOutput"] = {
                **summary.get("incompleteOutput", {}),
                "minOutputMB": args.min_output_mb,
                "actualOutputMB": round(output_mb, 2),
            }

        print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
        if args.report_dir:
            report_dir = Path(args.report_dir)
            report_dir.mkdir(parents=True, exist_ok=True)
            (report_dir / "summary.json").write_text(
                json.dumps(summary, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        return 1 if failures or stats["files"] < args.requests or (args.min_output_mb and output_mb < args.min_output_mb) else 0
    finally:
        dump_jfr(process, args.jfr_file)
        stop_service(process)


if __name__ == "__main__":
    sys.exit(main())
