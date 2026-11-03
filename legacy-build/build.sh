#!/usr/bin/env bash
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
#
#   ./build.sh                       — собрать текущую версию из AndroidManifest.xml
#   ./build.sh 2 1.1 "Что нового"    — поднять версию (versionCode, versionName) и собрать
#   FORCE=1 ./build.sh 3 1.2 "..."   — обязательное обновление: старым версиям модалку не закрыть
#
# Результат в dist/: smart-player-<code>.apk, smart-player.apk (последняя) + version.json — залить в magicbtn.online/ai-video/
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/android-sdk; [ -d "${ANDROID_HOME:-}/build-tools/35.0.0" ] && SDK=$ANDROID_HOME
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
OUT=build

if [ $# -ge 2 ]; then
  sed -i -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$1\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$2\"/" AndroidManifest.xml
fi
CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)
NAME=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)
NOTES=${3:-}

[ -f release.keystore ] || { echo "нет release.keystore — без него обновления не встанут поверх"; exit 1; }
# shellcheck disable=SC1091
source keystore.properties

rm -rf $OUT && mkdir -p $OUT/res $OUT/classes $OUT/dex dist
$BT/aapt2 compile --dir res -o $OUT/res/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $JAR -A assets --manifest AndroidManifest.xml \
  --rename-manifest-package online.eyetrick.hnarchive \
  --java $OUT/gen $OUT/res/res.zip --min-sdk-version 24 --target-sdk-version 35
javac --release 11 -nowarn -Xlint:-options -encoding UTF-8 -cp $JAR -d $OUT/classes \
  $(find src $OUT/gen -name '*.java')
$BT/d8 --release --min-api 24 --lib $JAR --output $OUT/dex $(find $OUT/classes -name '*.class')
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
APK=dist/smart-player-$CODE.apk
$BT/apksigner sign --v4-signing-enabled false --ks release.keystore --ks-key-alias hn --ks-pass pass:"$KS_PASS" --out "$APK" $OUT/aligned.apk
$BT/apksigner verify "$APK"
cp "$APK" smart-player.apk
cp "$APK" dist/e.apk   # копия последней версии (старые установки 1.x–2.1 обновляются через прежний адрес)
cp "$APK" dist/smart-player.apk

MIN=1; [ "${FORCE:-}" = 1 ] && MIN=$CODE
python3 - "$CODE" "$NAME" "$NOTES" "$MIN" "$(basename "$APK")" > dist/version.json <<'PY'
import json, sys
code, name, notes, mincode, apk = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": apk,
                  "notes": notes, "minVersionCode": int(mincode)}, ensure_ascii=False, indent=2))
PY
echo "готово: $APK (v$NAME, code $CODE)"; cat dist/version.json
