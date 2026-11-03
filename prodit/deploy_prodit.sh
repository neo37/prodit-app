#!/usr/bin/env bash
# Сервер приложения Production IT на spacecode.business (144.91.83.27):
#   https://spacecode.business/prodit/video_api.php   — тот же video_api.php, что у Умного плеера, + prodit_ext.php
#   wss://spacecode.business/prodit/connection/websocket — Centrifugo (мгновенная доставка лички и звонков)
# Своя база (/opt/prodit/vapi_data), свои пользователи. Секреты — /opt/prodit/.env (создаётся один раз, не перезаписывается),
# почта — те же ящики, что у umno.cloud (~/send_mail_umno.py), ключ встреч — ~/infra/spacecode-site/meet/.meet_key.
# Админка: https://spacecode.business/prodit/video_api.php?action=prodit_admin&key=<ключ> (ключ печатается в конце).
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=root@144.91.83.27
R=/opt/prodit

ssh "$HOST" "mkdir -p $R/www $R/vapi_data $R/centrifugo && chown 33:33 $R/vapi_data && chmod 700 $R/vapi_data"

echo "== секреты (один раз)"
if ! ssh "$HOST" "test -f $R/.env"; then
  ENVFILE=$(mktemp); trap 'rm -f "$ENVFILE"' EXIT
  python3 - "$HOME/send_mail_umno.py" "$(cat ~/infra/spacecode-site/meet/.meet_key)" > "$ENVFILE" <<'PY'
import ast, secrets, sys
src = open(sys.argv[1], encoding="utf-8").read()
v = {}
for n in ast.parse(src).body:
    if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name) and n.targets[0].id in ("PRIMARY", "FALLBACK"):
        v[n.targets[0].id] = ast.literal_eval(n.value)
p, f = v["PRIMARY"], v["FALLBACK"]
print(f"ADMIN_KEY={secrets.token_urlsafe(24)}")
print(f"MEET_KEY={sys.argv[2]}")
print(f"RT_SECRET={secrets.token_urlsafe(32)}\nRT_API_KEY={secrets.token_urlsafe(24)}\nRT_API=http://centrifugo:8000")
print(f"SMTP_HOST={p['host']}\nSMTP_PORT={p['port']}\nSMTP_USER={p['user']}\nSMTP_PASS={p['password']}")
print(f"SMTP2_HOST={f['host']}\nSMTP2_PORT={f['port']}\nSMTP2_USER={f['user']}\nSMTP2_PASS={f['password']}")
PY
  scp -q "$ENVFILE" "$HOST":$R/.env
  ssh "$HOST" "chmod 600 $R/.env"
fi

echo "== код"
ADMIN_KEY=$(ssh "$HOST" "grep ^ADMIN_KEY= $R/.env | cut -d= -f2-")
TMP=$(mktemp -d)
python3 - php/video_api.php "$TMP/video_api.php" "$ADMIN_KEY" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"const API_KEY\s*=\s*'[^']*';", f"const API_KEY = '{sys.argv[3]}';", s, count=1)
s = re.sub(r"const FOLDERS\s*=\s*\[.*?\];", "const FOLDERS = [];", s, count=1, flags=re.S)        # видеопапок сайта тут нет
s = re.sub(r"const PUBLIC_FOLDERS\s*=\s*\[[^\]]*\];", "const PUBLIC_FOLDERS = [];", s, count=1)
open(sys.argv[2], "w", encoding="utf-8").write(s)
PY
scp -q "$TMP/video_api.php" php/prodit_ext.php prodit/store/privacy.html "$HOST":$R/www/
scp -q fcm-service-account.json "$HOST":$R/vapi_data/fcm-service-account.json
rm -rf "$TMP"
ssh "$HOST" "chown 33:33 $R/vapi_data/fcm-service-account.json && chmod 600 $R/vapi_data/fcm-service-account.json"

echo "== Centrifugo и PHP (docker compose)"
ssh "$HOST" "cd $R && set -a && . ./.env && set +a && python3 - <<'PY'
import json, os
json.dump({
  'client': {'token': {'hmac_secret_key': os.environ['RT_SECRET']}, 'allowed_origins': ['https://spacecode.business']},
  'http_api': {'key': os.environ['RT_API_KEY']},
  'channel': {'namespaces': [{'name': 'dm', 'allow_user_limited_channels': True}]},
}, open('centrifugo/config.json', 'w'), indent=2)
PY
chmod 644 centrifugo/config.json
cat > compose.yml <<'YML'
services:
  php:
    image: php:8.3-apache
    restart: unless-stopped
    env_file: .env
    environment: { PRODIT_CODE_GAP: '50' }
    volumes:
      - ./www:/var/www/html:ro
      - ./vapi_data:/var/vapi_data
    ports: ['127.0.0.1:8830:80']
    mem_limit: 512m
  centrifugo:
    image: centrifugo/centrifugo:v6
    restart: unless-stopped
    command: centrifugo -c /centrifugo/config.json
    volumes: ['./centrifugo:/centrifugo:ro']
    ports: ['127.0.0.1:8831:8000']
    mem_limit: 256m
    ulimits: { nofile: { soft: 65536, hard: 65536 } }
YML
docker compose up -d --remove-orphans && docker compose restart php >/dev/null && docker compose ps --format '{{.Name}} {{.Status}}'"

echo "== nginx: /prodit/ → 127.0.0.1:8830, /prodit/connection/ → Centrifugo"
ssh "$HOST" 'python3 - <<PY
import re, shutil, subprocess, time
f = "/etc/nginx/sites-available/spacecode.business"
s = open(f).read()
if "location /prodit/" in s:
    print("уже есть")
    raise SystemExit
bak = f + ".bak-prodit-" + time.strftime("%Y%m%d-%H%M")
shutil.copy(f, bak)
loc = ("    location /prodit/connection/ { proxy_pass http://127.0.0.1:8831/connection/; proxy_http_version 1.1;"
       " proxy_set_header Upgrade \$http_upgrade; proxy_set_header Connection \"upgrade\"; proxy_set_header Host \$host;"
       " proxy_read_timeout 3600s; }\n"
       "    location /prodit/ { proxy_pass http://127.0.0.1:8830/; proxy_set_header Host \$host;"
       " proxy_set_header X-Real-IP \$remote_addr; client_max_body_size 8m; proxy_read_timeout 120s; }\n")
parts = re.split(r"(?=\bserver\s*\{)", s)
out = []
for p in parts:
    if "443" in p and "location" in p:
        i = p.index("location")
        p = p[:i] + loc + "    " + p[i:]
    out.append(p)
open(f, "w").write("".join(out))
if subprocess.run(["nginx", "-t"]).returncode != 0:
    shutil.copy(bak, f); print("nginx -t не прошёл — откат"); raise SystemExit(1)
subprocess.run(["systemctl", "reload", "nginx"], check=True); print("добавлено, бэкап", bak)
PY'

echo "== проверка"
sleep 2
curl -s -m 15 "https://spacecode.business/prodit/video_api.php?action=prodit_config"; echo
curl -s -o /dev/null -w "админка без ключа: %{http_code}\n" -m 15 "https://spacecode.business/prodit/video_api.php?action=prodit_admin"
echo "админка: https://spacecode.business/prodit/video_api.php?action=prodit_admin&key=$ADMIN_KEY"
