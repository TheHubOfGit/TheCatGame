#!/usr/bin/env bash
# Builds the Cat Toy APK without Gradle (aapt2 + javac + d8 + apksigner).
# Usage: ./build.sh            -> build/CatToy.apk
#        ./build.sh install    -> build, install, and launch on the projector
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
DEVICE="${DEVICE:-192.168.1.241:5555}"
KEYSTORE=".keystore/debug.keystore"

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml -A app/assets --java build/gen \
  --min-sdk-version 24 --target-sdk-version 30 build/res.zip

javac -nowarn --release 11 -classpath "$PLATFORM" -d build/classes \
  $(find app/src build/gen -name '*.java') 2> >(grep -v 'warning: \[options\]' >&2)

"$BT/d8" --min-api 24 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p .keystore
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias cattoy -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Cat Toy" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out build/CatToy.apk build/aligned.apk
echo "Built build/CatToy.apk"

if [ "${1:-}" = "install" ]; then
  adb connect "$DEVICE" >/dev/null
  adb -s "$DEVICE" install -r build/CatToy.apk
  adb -s "$DEVICE" shell am start -n com.cashewpeanut.cattoy/.MainActivity
fi
