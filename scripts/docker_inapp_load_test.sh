#!/bin/sh
set -eu

CONTAINER_NAME="${1:-dcc-step-app}"
REQUESTS="${REQUESTS:-100}"
PREFIX="${PREFIX:-INAPPLOAD}"

for i in $(seq 0 $((REQUESTS - 1))); do
  cat >"/tmp/${PREFIX}_${i}.json" <<EOF
{"requestId":"${PREFIX}_${i}","sm4Key":"2123433411630000","ip":"55.51.53.74","fieldsToEncrypt":["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]}
EOF
done

for i in $(seq 0 $((REQUESTS - 1))); do
  curl -s -o "/tmp/${PREFIX}_${i}.out" -w "%{http_code}\n" \
    -X POST http://127.0.0.1:8080/encrypt \
    -H "Content-Type: application/json" \
    --data-binary "@/tmp/${PREFIX}_${i}.json" &
done

wait
