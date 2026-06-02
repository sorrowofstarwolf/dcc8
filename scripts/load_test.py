import argparse
import concurrent.futures
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


DEFAULT_FIELDS = [
    "user_id",
    "serial_no",
    "user_code",
    "business_key",
    "device_id",
    "trans_id",
    "secret_code",
    "name",
]


@dataclass
class RequestResult:
    request_id: str
    status_code: int
    elapsed_ms: float
    body: str


def post_json(url: str, payload: dict, timeout_seconds: float) -> RequestResult:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8", errors="replace")
            return RequestResult(
                request_id=payload["requestId"],
                status_code=response.getcode(),
                elapsed_ms=(time.perf_counter() - start) * 1000.0,
                body=response_body,
            )
    except urllib.error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")
        return RequestResult(
            request_id=payload["requestId"],
            status_code=exc.code,
            elapsed_ms=(time.perf_counter() - start) * 1000.0,
            body=response_body,
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Concurrent load test for /encrypt")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=100)
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    parser.add_argument("--sm4-key", default="2123433411630000")
    parser.add_argument("--ip", default="55.51.53.74")
    parser.add_argument("--request-prefix", default="EXTREME")
    parser.add_argument("--output-dir")
    parser.add_argument("--expected-files", type=int)
    parser.add_argument("--wait-timeout-seconds", type=float, default=600.0)
    parser.add_argument("--stable-polls", type=int, default=3)
    parser.add_argument(
        "--fields-json",
        default=json.dumps(DEFAULT_FIELDS, ensure_ascii=True),
        help="JSON array of fieldsToEncrypt",
    )
    return parser.parse_args()


def wait_for_output(output_dir: str, expected_files: int, timeout_seconds: float, stable_polls: int, request_prefix: str) -> dict:
    deadline = time.time() + timeout_seconds
    stable = 0
    last_total_bytes = -1
    prefix = request_prefix + "_"
    while time.time() < deadline:
        files = [
            entry
            for entry in os.scandir(output_dir)
            if entry.is_file() and entry.name.endswith(".csv") and entry.name.startswith(prefix)
        ]
        total_bytes = sum(entry.stat().st_size for entry in files)
        if len(files) == expected_files and total_bytes > 0 and total_bytes == last_total_bytes:
            stable += 1
        else:
            stable = 0
            last_total_bytes = total_bytes
        print(
            json.dumps(
                {
                    "progressFiles": len(files),
                    "expectedFiles": expected_files,
                    "totalOutputMB": round(total_bytes / (1024.0 * 1024.0), 2),
                    "stablePolls": stable,
                },
                ensure_ascii=False,
            )
        )
        if stable >= stable_polls:
            return {
                "files": len(files),
                "totalBytes": total_bytes,
                "stablePolls": stable,
            }
        time.sleep(1.0)
    raise TimeoutError(f"output directory did not stabilize within {timeout_seconds} seconds: {output_dir}")


def main() -> int:
    args = parse_args()
    try:
        fields = json.loads(args.fields_json)
    except json.JSONDecodeError as exc:
        print(f"invalid --fields-json: {exc}", file=sys.stderr)
        return 2
    if not isinstance(fields, list) or not fields or not all(isinstance(item, str) for item in fields):
        print("--fields-json must be a non-empty JSON string array", file=sys.stderr)
        return 2

    payloads = [
        {
            "requestId": f"{args.request_prefix}_{i:03d}",
            "sm4Key": args.sm4_key,
            "ip": args.ip,
            "fieldsToEncrypt": fields,
        }
        for i in range(args.requests)
    ]

    start = time.perf_counter()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(
                post_json,
                f"{args.base_url.rstrip('/')}/encrypt",
                payload,
                args.timeout_seconds,
            )
            for payload in payloads
        ]
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())

    elapsed_ms = (time.perf_counter() - start) * 1000.0
    failures = [result for result in results if result.status_code != 200]
    accepted = sum(1 for result in results if result.status_code == 200 and '"status":"ACCEPTED"' in result.body.replace(" ", ""))
    slowest_ms = max((result.elapsed_ms for result in results), default=0.0)
    fastest_ms = min((result.elapsed_ms for result in results), default=0.0)
    average_ms = sum(result.elapsed_ms for result in results) / max(1, len(results))

    summary = {
        "baseUrl": args.base_url,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "fields": fields,
        "accepted": accepted,
        "failures": len(failures),
        "elapsedMs": round(elapsed_ms, 2),
        "fastestMs": round(fastest_ms, 2),
        "slowestMs": round(slowest_ms, 2),
        "averageMs": round(average_ms, 2),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if failures:
        print("failed requests:", file=sys.stderr)
        for failure in failures[:10]:
            print(
                json.dumps(
                    {
                        "requestId": failure.request_id,
                        "statusCode": failure.status_code,
                        "body": failure.body,
                    },
                    ensure_ascii=False,
                ),
                file=sys.stderr,
            )
        return 1

    if args.output_dir:
        output_summary = wait_for_output(
            output_dir=args.output_dir,
            expected_files=args.expected_files or args.requests,
            timeout_seconds=args.wait_timeout_seconds,
            stable_polls=args.stable_polls,
            request_prefix=args.request_prefix,
        )
        print(
            json.dumps(
                {
                    "outputDir": args.output_dir,
                    "files": output_summary["files"],
                    "totalOutputMB": round(output_summary["totalBytes"] / (1024.0 * 1024.0), 2),
                    "stablePolls": output_summary["stablePolls"],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
