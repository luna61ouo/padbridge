#!/bin/bash
# 做發布檔：dist/padbridge-<版本>.apk 與 dist/PadbridgeSetup-windows-<版本>.zip（上傳到 GitHub Releases）。
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
rm -rf dist && mkdir -p dist
cp build/padbridge.apk "dist/padbridge-$VER.apk"
# 跟 APK 裡給區網下載的是同一個檔（build.sh 產的），兩邊 SHA-256 一致
cp build/assets/PadbridgeSetup-windows.zip "dist/PadbridgeSetup-windows-$VER.zip"
[ -f build/assets/PadbridgeSetup-windows.zip.sig ] || { echo "沒有發布簽名，停"; exit 1; }
cp build/assets/PadbridgeSetup-windows.zip.sig "dist/PadbridgeSetup-windows-$VER.zip.sig"
( cd dist && sha256sum * > SHA256SUMS.txt )
ls -la dist
