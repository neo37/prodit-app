#!/usr/bin/env bash
# AAB и APK приложения Production IT (вариант prodit: пакет com.prodit.meet, targetSdk 36, ключ prodit-upload.keystore).
# Версия — prodit/version.properties (CODE +1 на каждую загрузку в Play). Результат: dist/prodit/production-it-<code>.{aab,apk}
set -euo pipefail
cd "$(dirname "$0")/.."
. prodit/version.properties
[ -f prodit-upload.keystore ] || { echo "нет prodit-upload.keystore"; exit 1; }
G=${GRADLE:-$(ls ~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle | head -1)}
(cd gradle-build && "$G" --no-daemon -q bundleProditRelease assembleProditRelease)
mkdir -p dist/prodit
cp gradle-build/build/outputs/bundle/proditRelease/smart-player-prodit-release.aab "dist/prodit/production-it-$CODE.aab"
cp gradle-build/build/outputs/apk/prodit/release/smart-player-prodit-release.apk "dist/prodit/production-it-$CODE.apk"
cp gradle-build/build/outputs/mapping/proditRelease/mapping.txt "dist/prodit/mapping-$CODE.txt"
python3 -c "import json;d=json.load(open('google-services.json'));import sys;sys.exit(0 if any(c['client_info']['android_client_info']['package_name']=='com.prodit.meet' for c in d['client']) else 1)" \
  || echo "ВНИМАНИЕ: в google-services.json нет com.prodit.meet — push не будет (запустите prodit/firebase_add_app.py)"
jarsigner -verify "dist/prodit/production-it-$CODE.aab" | tail -1
echo "готово: dist/prodit/production-it-$CODE.aab (v$NAME, code $CODE)"
