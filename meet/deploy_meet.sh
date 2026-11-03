#!/usr/bin/env bash
# Выкладка spacecode.business/meet: страница (через сайт), сервис рассылки (systemd), /meet/api/ в nginx.
# SMTP — те же ящики, что у umno.cloud (берутся из ~/send_mail_umno.py), код доступа — meet/.meet_key.
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=root@144.91.83.27
KEY=$(cat meet/.meet_key)

echo "== сайт"
python3 build.py >/dev/null
rsync -a --delete dist/ "$HOST":/var/www/spacecode/

echo "== сервис"
ENVFILE=$(mktemp); trap 'rm -f "$ENVFILE"' EXIT
python3 - "$HOME/send_mail_umno.py" "$KEY" > "$ENVFILE" <<'PY'
import ast, sys
src = open(sys.argv[1], encoding="utf-8").read()
v = {}
for n in ast.parse(src).body:
    if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name) and n.targets[0].id in ("PRIMARY", "FALLBACK"):
        v[n.targets[0].id] = ast.literal_eval(n.value)
p, f = v["PRIMARY"], v["FALLBACK"]
print(f"MEET_KEY={sys.argv[2]}")
print(f"SMTP_HOST={p['host']}\nSMTP_PORT={p['port']}\nSMTP_USER={p['user']}\nSMTP_PASS={p['password']}")
print(f"SMTP2_HOST={f['host']}\nSMTP2_PORT={f['port']}\nSMTP2_USER={f['user']}\nSMTP2_PASS={f['password']}")
PY
# уведомления о посетителях в личку Умного плеера: VISIT_TO, VISIT_TO_NAME, MAGICBTN_API_KEY (вне git)
[ -f meet/.visit.env ] && grep -v "^#" meet/.visit.env >> "$ENVFILE"
ssh "$HOST" 'mkdir -p /opt/spacecode-meet && install -m 600 /dev/null /etc/spacecode-meet.env'
scp -q meet/meet_api.py "$HOST":/opt/spacecode-meet/meet_api.py
scp -q "$ENVFILE" "$HOST":/etc/spacecode-meet.env
ssh "$HOST" 'chmod 600 /etc/spacecode-meet.env && cat > /etc/systemd/system/spacecode-meet.service <<UNIT
[Unit]
Description=spacecode.business/meet — встречи Production IT и рассылка приглашений
After=network-online.target

[Service]
EnvironmentFile=/etc/spacecode-meet.env
Environment=MEET_DATA=/var/lib/spacecode-meet
ExecStart=/usr/bin/python3 /opt/spacecode-meet/meet_api.py
DynamicUser=yes
StateDirectory=spacecode-meet
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload && systemctl enable --now spacecode-meet && systemctl restart spacecode-meet'

echo "== nginx: /meet/api/ → 127.0.0.1:8823"
ssh "$HOST" 'python3 - <<PY
import re, shutil, subprocess, time, glob
f = "/etc/nginx/sites-available/spacecode.business"   # только рабочий конфиг, не бэкапы рядом
s = open(f).read()
if "/meet/api/" in s and "X-Real-IP" in s.split("/meet/api/")[1].split("}")[0]:
    print("уже есть:", f)
elif "/meet/api/" in s:   # было без адреса клиента — добавить X-Real-IP (лимит без кода считается по IP)
    bak = f + ".bak-meet-" + time.strftime("%Y%m%d-%H%M")
    shutil.copy(f, bak)
    s = s.replace("proxy_pass http://127.0.0.1:8823; proxy_set_header Host \$host;",
                  "proxy_pass http://127.0.0.1:8823; proxy_set_header Host \$host; proxy_set_header X-Real-IP \$remote_addr;")
    open(f, "w").write(s)
    if subprocess.run(["nginx", "-t"]).returncode != 0:
        shutil.copy(bak, f); print("nginx -t не прошёл — откат"); raise SystemExit(1)
    subprocess.run(["systemctl", "reload", "nginx"], check=True); print("X-Real-IP добавлен в", f)
else:
    bak = f + ".bak-meet-" + time.strftime("%Y%m%d-%H%M")
    shutil.copy(f, bak)
    loc = "\n    location /meet/api/ { proxy_pass http://127.0.0.1:8823; proxy_set_header Host \$host; proxy_set_header X-Real-IP \$remote_addr; client_max_body_size 32k; }\n"
    # в каждый server-блок с ssl (443) — перед первым location
    parts = re.split(r"(?=\bserver\s*\{)", s)
    out = []
    for p in parts:
        if "443" in p and "location" in p:
            i = p.index("location")
            p = p[:i] + loc.lstrip("\n") + "\n    " + p[i:]
        out.append(p)
    open(f, "w").write("".join(out))
    if subprocess.run(["nginx", "-t"]).returncode != 0:
        shutil.copy(bak, f); print("nginx -t не прошёл — откат"); raise SystemExit(1)
    subprocess.run(["systemctl", "reload", "nginx"], check=True); print("добавлено в", f, "бэкап", bak)
PY'

echo "== проверка"
sleep 1
curl -s -m 15 https://spacecode.business/meet/api/health; echo
curl -s -o /dev/null -w "страница %{http_code}\n" -m 15 https://spacecode.business/meet/
echo "код доступа: $KEY"
