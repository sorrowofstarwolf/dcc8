#!/bin/sh
for i in 0 1 2 3 4 5 6 7 8 9; do
  cat >/tmp/inapp_$i.json <<EOF
{"requestId":"INAPP10_$i","sm4Key":"2123433411630000","ip":"55.51.53.74","fieldsToEncrypt":["user_id","serial_no","user_code","business_key","device_id","trans_id","secret_code","name"]}
EOF
  curl -s -o /tmp/inapp_$i.out -w "%{http_code}\n" -X POST http://127.0.0.1:8080/encrypt -H "Content-Type: application/json" --data-binary @/tmp/inapp_$i.json &
done
wait
