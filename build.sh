#!/bin/bash
# Build Padbridge APK — 不用 Gradle：aapt2 + javac + d8 + apksigner。
# 產出：build/padbridge.apk
#
# 需要：Android SDK（platform android-35、build-tools 35.0.0）、JDK 11+。
# 環境變數（可寫進同資料夾的 build.local.env，不會進版控）：
#   ANDROID_HOME              SDK 位置（預設 ~/Android/Sdk）
#   PADBRIDGE_KEYSTORE        簽章金鑰（預設 ~/.android/padbridge-debug.jks，沒有就自動產一把 debug 用的）
#   PADBRIDGE_KS_PASS_FILE    金鑰密碼檔（沒給就用 PADBRIDGE_KS_PASS，再沒有就是 debug 預設 android）
#   PADBRIDGE_KEY_ALIAS       金鑰別名（預設 padbridge）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
[ -f build.local.env ] && . ./build.local.env

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
BT="$ANDROID_HOME/build-tools/35.0.0"
KEYSTORE="${PADBRIDGE_KEYSTORE:-$HOME/.android/padbridge-debug.jks}"
KEY_ALIAS="${PADBRIDGE_KEY_ALIAS:-padbridge}"
if [ -n "${PADBRIDGE_KS_PASS_FILE:-}" ]; then KS_PASS=$(cat "$PADBRIDGE_KS_PASS_FILE"); else KS_PASS="${PADBRIDGE_KS_PASS:-android}"; fi
if [ ! -f "$KEYSTORE" ]; then
    echo "沒有簽章金鑰，產一把 debug 用的：$KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=Padbridge Debug" >/dev/null
fi

rm -rf build && mkdir -p build/gen build/classes build/assets/PadbridgeSetup
# Windows 通用安裝包（不含任何配對碼）：打進 APK 讓手機能在區網提供下載；release.sh 也直接拿這一個上傳，
# 所以 GitHub 上的跟手機給的是同一個檔（SHA-256 一樣）。時間戳固定，重建內容不變時位元組也不變。
cp windows/PadbridgeSetup.cmd windows/install.ps1 windows/PadbridgeReceiver.ps1 windows/PadbridgeQr.cs windows/PadbridgeFrames.cs \
   LICENSE NOTICE build/assets/PadbridgeSetup/
touch -d '2026-01-01 00:00:00 UTC' build/assets/PadbridgeSetup build/assets/PadbridgeSetup/*
( cd build/assets && find PadbridgeSetup | LC_ALL=C sort | zip -qX -@ PadbridgeSetup-windows.zip && rm -rf PadbridgeSetup )
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 35 \
    --auto-add-overlay -I "$ANDROID_JAR" -R build/res.zip -A build/assets --java build/gen -o build/app-no-dex.apk
find src build/gen -name '*.java' | sort > build/srclist.txt
# 第三方：libs/*.jar（ZXing core 3.5.3，Apache-2.0，掃配對 QR 用；Maven Central 下載、sha1 驗過）
LIBS=$(ls libs/*.jar 2>/dev/null | tr '\n' ':')
javac --release 11 -Xlint:-options -d build/classes -classpath "$ANDROID_JAR:$LIBS" @build/srclist.txt
find build/classes -name '*.class' > build/classlist.txt
"$BT/d8" --release --output build/ --min-api 29 --lib "$ANDROID_JAR" @build/classlist.txt libs/*.jar
( cd build && zip -q -j app-no-dex.apk classes.dex && mv app-no-dex.apk app-unsigned.apk )
"$BT/zipalign" -f -p 4 build/app-unsigned.apk build/app-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out build/padbridge.apk build/app-aligned.apk
"$BT/apksigner" verify build/padbridge.apk
echo "✓ APK: $ROOT/build/padbridge.apk ($(stat -c%s build/padbridge.apk) bytes)"
