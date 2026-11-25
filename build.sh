#!/bin/bash
# Build Padbridge APK：先產 Windows 安裝包（打進 APK 的 assets），再交給 Gradle 編譯、簽章。
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

rm -rf build && mkdir -p build/assets/PadbridgeSetup
# Windows 通用安裝包（不含任何配對碼）：打進 APK 讓手機能在區網提供下載；release.sh 也直接拿這一個上傳，
# 所以 GitHub 上的跟手機給的是同一個檔（SHA-256 一樣）。時間戳固定，重建內容不變時位元組也不變。
cp windows/PadbridgeSetup.cmd windows/install.ps1 windows/PadbridgeReceiver.ps1 windows/PadbridgeRestart.ps1 windows/PadbridgeQr.cs windows/PadbridgeFrames.cs windows/PadbridgeKeys.cs windows/PadbridgeUpdate.cs \
   LICENSE NOTICE build/assets/PadbridgeSetup/
# 版本號放進安裝包（跟著簽名）：電腦端靠它判斷新舊、回報給手機，也擋「拿舊的簽名包降版」
sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | tr -d '\n' > build/assets/PadbridgeSetup/version.txt
touch -d '2026-01-01 00:00:00 UTC' build/assets/PadbridgeSetup build/assets/PadbridgeSetup/*
( cd build/assets && find PadbridgeSetup | LC_ALL=C sort | zip -qX -@ PadbridgeSetup-windows.zip && rm -rf PadbridgeSetup )
# 發布簽名（電腦端自動更新只裝簽過的）：PADBRIDGE_UPDATE_KEY＝RSA 私鑰 PEM。沒有就不簽（自己編的版本不能自動更新電腦端）。
if [ -n "${PADBRIDGE_UPDATE_KEY:-}" ] && [ -f "$PADBRIDGE_UPDATE_KEY" ]; then
    openssl dgst -sha256 -sign "$PADBRIDGE_UPDATE_KEY" build/assets/PadbridgeSetup-windows.zip | base64 -w0 > build/assets/PadbridgeSetup-windows.zip.sig
else
    echo "（沒有 PADBRIDGE_UPDATE_KEY：電腦端安裝包不簽名，這版不能用自動更新推給電腦）"
fi
# 編譯、打包、簽章交給 Gradle（11-23 起；手機端要用 Google 手錶通訊函式庫，Play 上架也要 Gradle）。
# 簽章金鑰照舊從 build.local.env 的 PADBRIDGE_KEYSTORE／PADBRIDGE_KS_PASS_FILE 來；沒有就用 debug 金鑰。
export PADBRIDGE_KEYSTORE="$KEYSTORE" KEY_ALIAS PADBRIDGE_KS_PASS_FILE PADBRIDGE_KEY_ALIAS="$KEY_ALIAS" ANDROID_HOME
[ -n "${PADBRIDGE_KS_PASS_FILE:-}" ] || export PADBRIDGE_KS_PASS="$KS_PASS"
./gradlew --no-daemon -q :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk build/padbridge.apk
"$BT/apksigner" verify build/padbridge.apk
echo "✓ APK: $ROOT/build/padbridge.apk ($(stat -c%s build/padbridge.apk) bytes)"
