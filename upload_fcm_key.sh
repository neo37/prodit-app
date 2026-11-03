#!/usr/bin/env bash
# Ключ сервисного аккаунта Firebase (fcm-service-account.json) → /vapi_data на magicbtn (вне www, права 600).
# После этого сервер начинает рассылать пуши (личка, звонки, общий чат, комментарии).
set -euo pipefail
cd "$(dirname "$0")"
[ -f fcm-service-account.json ] || { echo "нет fcm-service-account.json"; exit 1; }
source .ftp.env
HOST=${FTP_URL:-${FTP_HOST:-31.31.197.48}}; HOST=${HOST#*://}; HOST=${HOST%%/*}
LFTP_PASSWORD="$FTP_PASS" lftp -c "set ssl:verify-certificate no; set net:max-retries 2; \
open --env-password -u ${FTP_USER:?в .ftp.env нет FTP_USER} $HOST; put fcm-service-account.json -o /vapi_data/fcm-service-account.json; \
chmod 600 /vapi_data/fcm-service-account.json; cls -l /vapi_data/fcm-service-account.json"
echo "готово: ключ на сервере"
echo "проверка (ключ НЕ должен открываться по ссылке, ждём 404):"
curl -s -o /dev/null -w '%{http_code}\n' https://magicbtn.online/../vapi_data/fcm-service-account.json
