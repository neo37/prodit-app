#!/usr/bin/env bash
# Заливка на magicbtn.online (FTP 31.31.197.48): video_api.php с сохранением боевых настроек + APK/version.json.
#
#   ./deploy_ftp.sh          — пароль берётся из .ftp.env (FTP_PASS=..., права 600, в git не класть)
#
# video_api.php: с сервера скачивается текущий, из него берётся блок настроек (API_KEY … перед SOCIAL_DB)
# и подставляется в новый; старый остаётся бэкапом в /vapi_data/video_api.php.bak-<дата>.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .ftp.env ] || { echo "нет .ftp.env (FTP_PASS=...)"; exit 1; }
# shellcheck disable=SC1091
source .ftp.env
HOST=${FTP_URL:-${FTP_HOST:-31.31.197.48}}
HOST=${HOST#*://}; HOST=${HOST%%/*}   # FTP_URL может быть ftp://host/ — нужен только host
USER=${FTP_USER:?в .ftp.env нет FTP_USER}
WWW=/www/magicbtn.online
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
STAMP=$(date +%Y%m%d-%H%M)
CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)   # версия из манифеста; файлы — из dist/
cp dist/smart-player-$CODE.apk site/ai-video/smart-player-$CODE.apk
cp dist/smart-player-$CODE.apk site/ai-video/smart-player.apk
cp dist/version.json site/ai-video/version.json

# команды одной строкой (перевод строки ломает разбор у lftp), пароль — через окружение, не в командной строке
ftp() {
  LFTP_PASSWORD="$FTP_PASS" lftp -c "set ssl:verify-certificate no; set net:max-retries 2; \
open --env-password -u $USER $HOST; $(echo "$1" | tr '\n' ' ')"
}

echo "== скачиваю текущий video_api.php"
ftp "get $WWW/video_api.php -o $TMP/remote.php"
grep -q "^const API_KEY" "$TMP/remote.php" || { echo "на сервере нет const API_KEY — стоп"; exit 1; }

python3 - "$TMP/remote.php" php/video_api.php "$TMP/video_api.php" <<'PY'
import sys
remote, local, out = (open(p, encoding="utf-8").read().split("\n") if i < 2 else p for i, p in enumerate(sys.argv[1:]))
def block(lines):
    a = next(i for i, l in enumerate(lines) if l.startswith("const API_KEY"))
    b = next(i for i, l in enumerate(lines) if l.startswith("const SOCIAL_DB"))
    return a, b
ra, rb = block(remote)
la, lb = block(local)
new = local[:la] + remote[ra:rb] + local[lb:]
if "'change-me'" in "\n".join(remote[ra:rb]):
    sys.exit("на сервере API_KEY = change-me — что-то не так, стоп")
open(out, "w", encoding="utf-8").write("\n".join(new))
print("настройки с сервера перенесены (%d строк)" % (rb - ra))
PY

echo "== заливаю"
ftp "put $TMP/remote.php -o /vapi_data/video_api.php.bak-$STAMP;
     put $TMP/video_api.php -o $WWW/video_api.php;
     put site/ai-video/smart-player-$CODE.apk -o $WWW/ai-video/smart-player-$CODE.apk;
     put site/ai-video/smart-player.apk -o $WWW/ai-video/smart-player.apk;
     put site/ai-video/smart-player.apk -o $WWW/a.apk;
     put site/ai-video/version.json -o $WWW/ai-video/version.json;
     put site/ai-video/privacy.htm -o $WWW/ai-video/privacy.htm"

echo "== проверка"
curl -s -m 15 https://magicbtn.online/ai-video/version.json | head -4
curl -s -m 15 -H 'X-UID: deploycheck0001' 'https://magicbtn.online/video_api.php?action=users' | head -c 200; echo
