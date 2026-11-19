#!/bin/bash
# 做發布檔：dist/padbridge-<版本>.apk 與 dist/PadbridgeSetup-windows-<版本>.zip（上傳到 GitHub Releases）。
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
rm -rf dist && mkdir -p dist/PadbridgeSetup
cp build/padbridge.apk "dist/padbridge-$VER.apk"
cp windows/PadbridgeSetup.cmd windows/install.ps1 windows/PadbridgeReceiver.ps1 windows/PadbridgeBleKick.ps1 \
   windows/PadbridgeBlePair.ps1 windows/PadbridgeQr.cs LICENSE NOTICE dist/PadbridgeSetup/
( cd dist && zip -qr "PadbridgeSetup-windows-$VER.zip" PadbridgeSetup && rm -rf PadbridgeSetup )
( cd dist && sha256sum * > SHA256SUMS.txt )
ls -la dist
