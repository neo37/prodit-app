#!/usr/bin/env bash
# Сборка AAB для Google Play без Gradle: aapt2 (proto) -> javac -> d8 -> bundletool -> jarsigner
#
#   ./build_play.sh            — версия берётся из AndroidManifest.xml (поднимать через build.sh)
#
# Отличия от build.sh: targetSdk 36 (требование Play для новых приложений с 31.08.2026),
# нет REQUEST_INSTALL_PACKAGES и обновления по воздуху (Flags.PLAY=true) — обновляет магазин.
# Подпись — play-upload.keystore (ключ загрузки, CN=Smart Player, O=magicbtn.online); итоговую ставит Google (Play App Signing).
# Пакет в Play — online.magicbtn.player (APK с сайта остаётся online.eyetrick.hnarchive — ради обновлений у установивших).
# Результат: dist/play/hn-archive-<code>.aab
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/android-sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-36/android.jar
BUNDLETOOL=${BUNDLETOOL:-$SDK/bundletool-all-1.17.2.jar}   # github.com/google/bundletool/releases
OUT=build-play

[ -f play-upload.keystore ] || { echo "нет play-upload.keystore"; exit 1; }
# shellcheck disable=SC1091
source play-keystore.properties
CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)
NAME=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)

rm -rf $OUT && mkdir -p $OUT/res $OUT/classes $OUT/dex $OUT/src $OUT/module dist/play
# манифест для Play: без установки пакетов и без приёмника установки
sed -e '/REQUEST_INSTALL_PACKAGES/d' \
    -e 's/android:targetSdkVersion="[0-9]*"/android:targetSdkVersion="36"/' \
    -e '/<receiver/,/\/>/d' AndroidManifest.xml > $OUT/AndroidManifest.xml
# исходники с Flags.PLAY = true
cp -r src/. $OUT/src/
sed -i 's/static final boolean PLAY = false;/static final boolean PLAY = true;/' $OUT/src/online/magicbtn/player/Flags.java

$BT/aapt2 compile --dir res -o $OUT/res/res.zip
$BT/aapt2 link --proto-format -o $OUT/base.zip -I $JAR -A assets --manifest $OUT/AndroidManifest.xml \
  --java $OUT/gen $OUT/res/res.zip --min-sdk-version 24 --target-sdk-version 36 \
  --version-code "$CODE" --version-name "$NAME"
javac --release 11 -nowarn -Xlint:-options -encoding UTF-8 -cp $JAR -d $OUT/classes \
  $(find $OUT/src $OUT/gen -name '*.java')
$BT/d8 --release --min-api 24 --lib $JAR --output $OUT/dex $(find $OUT/classes -name '*.class')

# модуль base: manifest/, dex/, res/, resources.pb
(cd $OUT/module && unzip -q ../base.zip && mkdir -p manifest dex && mv AndroidManifest.xml manifest/ && cp ../dex/classes.dex dex/ \
  && zip -qr ../base-module.zip .)
AAB=dist/play/smart-player-$CODE.aab
rm -f "$AAB"
java -jar "$BUNDLETOOL" build-bundle --modules=$OUT/base-module.zip --output="$AAB"
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore play-upload.keystore -storepass "$PLAY_KS_PASS" "$AAB" upload >/dev/null
jarsigner -verify "$AAB" | tail -1
echo "готово: $AAB (v$NAME, code $CODE, targetSdk 36)"
