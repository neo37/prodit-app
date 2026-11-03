#!/usr/bin/env bash
# Сборка APK для сайта (OTA) — с 3.4 через Gradle (gradle-build/, вариант site; нужен для Firebase).
#   ./build.sh                       — собрать текущую версию из AndroidManifest.xml
#   ./build.sh 26 3.5 "Что нового"   — поднять версию (versionCode, versionName) и собрать
#   FORCE=1 ./build.sh 26 3.5 "..."  — обязательное обновление
# Результат в dist/: smart-player-<code>.apk, smart-player.apk, e.apk + version.json — залить в magicbtn.online/ai-video/
# Старая сборка без Gradle (до 3.3) — legacy-build/.
set -euo pipefail
cd "$(dirname "$0")"
if [ $# -ge 2 ]; then
  sed -i -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$1\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$2\"/" AndroidManifest.xml
fi
CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)
NAME=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)
NOTES=${3:-}
[ -f release.keystore ] || { echo "нет release.keystore — без него обновления не встанут поверх"; exit 1; }
[ -f google-services.json ] || echo "ВНИМАНИЕ: нет google-services.json — сборка без пушей FCM"
G=${GRADLE:-$(ls ~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle | head -1)}
(cd gradle-build && "$G" --no-daemon -q assembleSiteRelease)
mkdir -p dist
APK=dist/smart-player-$CODE.apk
cp gradle-build/build/outputs/apk/site/release/smart-player-site-release.apk "$APK"
cp gradle-build/build/outputs/mapping/siteRelease/mapping.txt "dist/mapping-site-$CODE.txt"   # расшифровка падений (R8)
$HOME/android-sdk/build-tools/35.0.0/apksigner verify "$APK"
cp "$APK" smart-player.apk
cp "$APK" dist/e.apk
cp "$APK" dist/smart-player.apk
MIN=1; [ "${FORCE:-}" = 1 ] && MIN=$CODE
python3 - "$CODE" "$NAME" "$NOTES" "$MIN" "$(basename "$APK")" > dist/version.json <<'PY'
import json, sys
code, name, notes, mincode, apk = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": apk,
                  "notes": notes, "minVersionCode": int(mincode)}, ensure_ascii=False, indent=2))
PY
echo "готово: $APK (v$NAME, code $CODE)"; cat dist/version.json
