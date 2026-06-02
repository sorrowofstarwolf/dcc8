#!/usr/bin/env sh
set -eu

APP_CONTAINER="${APP_CONTAINER:-dcc-perf-app}"
IMAGE_NAME="${IMAGE_NAME:-dcc:local}"
HOST_PORT="${HOST_PORT:-18080}"
CONCURRENCY="${CONCURRENCY:-100}"
REQUESTS="${REQUESTS:-100}"
DATASET_HOST_PATH="${DATASET_HOST_PATH:-$PWD/perf_data_extreme_unique_300000.csv}"
DATASET_CONTAINER_PATH="${DATASET_CONTAINER_PATH:-/opt/app/dcc/perf_data_extreme_unique_300000.csv}"
OUTPUT_DIR_HOST="${OUTPUT_DIR_HOST:-$PWD/perf-output}"
REPORT_DIR_HOST="${REPORT_DIR_HOST:-$PWD/perf-reports}"
FIELDS_JSON="${FIELDS_JSON:-[\"user_id\",\"serial_no\",\"user_code\",\"business_key\",\"device_id\",\"trans_id\",\"secret_code\",\"name\"]}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
JFR_NAME="${JFR_NAME:-perf-$RUN_ID}"
OUTPUT_DIR_RUN="$OUTPUT_DIR_HOST/run-$RUN_ID"

mkdir -p "$OUTPUT_DIR_HOST" "$OUTPUT_DIR_RUN" "$REPORT_DIR_HOST"

echo "Building image $IMAGE_NAME"
mvn -q -DskipTests package
docker build -t "$IMAGE_NAME" .

echo "Removing old container if present"
docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true

echo "Starting app container with JFR"
docker run -d \
  --name "$APP_CONTAINER" \
  --entrypoint sh \
  --cpus=4 \
  --memory=8g \
  -p "$HOST_PORT:8080" \
  -v "$DATASET_HOST_PATH:$DATASET_CONTAINER_PATH:ro" \
  -v "$OUTPUT_DIR_RUN:/opt/app/dcc/output" \
  -v "$REPORT_DIR_HOST:/opt/app/dcc/perf-reports" \
  -e SERVER_PORT=8080 \
  -e DCC_DATASET_PATH="$DATASET_CONTAINER_PATH" \
  -e DCC_OUTPUT_DIR=/opt/app/dcc/output \
  -e DCC_EXPECTED_ROWS=300000 \
  -e DCC_CALLBACK_URL= \
  "$IMAGE_NAME" \
  -lc "nohup java -XX:StartFlightRecording=name=$JFR_NAME,filename=/opt/app/dcc/perf-reports/$JFR_NAME.jfr,settings=profile,dumponexit=true -jar /opt/app/dcc/app.jar >/opt/app/dcc/perf-reports/app.log 2>&1 & tail -f /opt/app/dcc/perf-reports/app.log"

echo "Waiting for /health"
healthy=0
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$HOST_PORT/health" >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 2
done
if [ "$healthy" -ne 1 ]; then
  echo "service did not become healthy on port $HOST_PORT" >&2
  exit 1
fi

echo "Running Python load test"
python scripts/load_test.py \
  --base-url "http://127.0.0.1:$HOST_PORT" \
  --requests "$REQUESTS" \
  --concurrency "$CONCURRENCY" \
  --fields-json "$FIELDS_JSON" \
  --output-dir "$OUTPUT_DIR_RUN" \
  --expected-files "$REQUESTS" \
  --request-prefix "EXTREME"

echo "Dumping JFR"
docker exec "$APP_CONTAINER" sh -lc "pid=\$(ps -ef | awk '/[j]ava -XX:StartFlightRecording/ {print \$2; exit}'); jcmd \$pid JFR.dump name=$JFR_NAME filename=/opt/app/dcc/perf-reports/$JFR_NAME.jfr"

echo "Analyzing JFR"
docker exec "$APP_CONTAINER" sh -lc "jfr summary /opt/app/dcc/perf-reports/$JFR_NAME.jfr > /opt/app/dcc/perf-reports/$JFR_NAME.summary.txt"
docker exec "$APP_CONTAINER" sh -lc "jfr print --events jdk.CPULoad,jdk.GarbageCollection,jdk.ThreadAllocationStatistics,jdk.ExecutionSample /opt/app/dcc/perf-reports/$JFR_NAME.jfr > /opt/app/dcc/perf-reports/$JFR_NAME.events.txt"

echo "Cleaning output CSV files"
docker exec "$APP_CONTAINER" sh -lc "rm -f /opt/app/dcc/output/*.csv"

echo "Stopping app container to flush JFR"
docker stop "$APP_CONTAINER" >/dev/null

echo "Reports written to:"
echo "  JFR:      $REPORT_DIR_HOST/$JFR_NAME.jfr"
echo "  Summary:  $REPORT_DIR_HOST/$JFR_NAME.summary.txt"
echo "  Events:   $REPORT_DIR_HOST/$JFR_NAME.events.txt"
echo "  App log:  $REPORT_DIR_HOST/app.log"
echo "  Output CSV files cleaned: $OUTPUT_DIR_RUN"
