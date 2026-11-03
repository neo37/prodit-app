#!/usr/bin/env bash
# AAB для Google Play — с 3.4 через Gradle (вариант play: пакет online.magicbtn.player, targetSdk 36,
# без установки APK, подпись ключом загрузки play-upload.keystore). Версия — из AndroidManifest.xml.
# Результат: dist/play/smart-player-<code>.aab
set -euo pipefail
cd "$(dirname "$0")"
CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)
NAME=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)
[ -f play-upload.keystore ] || { echo "нет play-upload.keystore"; exit 1; }
G=${GRADLE:-$(ls ~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle | head -1)}
(cd gradle-build && "$G" --no-daemon -q bundlePlayRelease)
mkdir -p dist/play
AAB=dist/play/smart-player-$CODE.aab
cp gradle-build/build/outputs/bundle/playRelease/smart-player-play-release.aab "$AAB"
cp gradle-build/build/outputs/mapping/playRelease/mapping.txt "dist/play/mapping-$CODE.txt"   # Play берёт его из AAB сам
jarsigner -verify "$AAB" | tail -1
echo "готово: $AAB (v$NAME, code $CODE, targetSdk 36)"
