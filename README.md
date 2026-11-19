# Padbridge

**把手機變成電腦的輸入中繼站。** ／ **Turn your phone into an input relay for your computer.**

[中文](#中文) · [English](#english)

---

## 中文

### 這是什麼

臨時要用滑鼠卻沒帶？要從沙發遠端操作電腦？好幾個人想輪流控制同一台電腦？
Padbridge 讓 Android 手機變成電腦的觸控板，透過 **Wi-Fi** 或 **藍牙（BLE）** 控制電腦游標。

滑鼠只是第一步。Padbridge 的目標是當「輸入中繼站」：手機接住藍牙搖桿、鍵盤、滑鼠、手錶手勢，再透過 Wi-Fi 轉給沒有藍牙、或不方便配對的電腦。

### 現在能做什麼

| | Wi-Fi 模式 | 藍牙（BLE）模式 |
|---|---|---|
| 電腦要裝東西嗎 | 要（Windows 小程式，右下角圖示） | 不用，電腦把手機當成藍牙滑鼠 |
| 一支手機控多台電腦 | 可以，App 裡切換 | 一次一台（電腦圖示可一鍵改連） |
| 多支手機控同一台電腦 | 可以，每支手機各自配對 | 可以 |
| 適合 | 電腦沒藍牙、要跟眼鏡／耳機等藍牙裝置並存 | 任何支援藍牙滑鼠的裝置（電腦、平板、VR） |

- 觸控板：單指移動、點一下左鍵、雙指點右鍵、雙指上下捲動、按住再滑拖曳
- 配對：電腦圖示顯示 QR，手機 App 掃一下就好
- 電腦端圖示：顯示連入的手機（名稱可自訂）、目前用 Wi-Fi 還是藍牙，可暫停、可撤銷某支手機

### 安裝

**手機（Android 10 以上）**：到 [Releases](../../releases) 下載 `padbridge-*.apk` 安裝。

**電腦（Windows 10／11）**：
1. 到 [Releases](../../releases) 下載 `PadbridgeSetup-windows-*.zip`，解壓縮
2. 雙擊 `PadbridgeSetup.cmd`
   - 出現「Windows 已保護您的電腦」→ 按「其他資訊」→「仍要執行」（安裝檔沒有付費數位簽章）
   - 問要不要允許變更 → 按「是」（要設定防火牆）
3. 右下角出現 Padbridge 圖示就裝好了，之後每次登入會自己啟動

手機 App 也找得到下載入口：「選電腦」→「電腦還沒裝 Padbridge？」，會顯示上面的 Releases 網址。網址不好打的話，可以讓電腦瀏覽器連到手機的區網 IP，網頁上自己選：
- **快速直接下載**：從手機拿同一個安裝包（不含任何配對碼）。走一般 HTTP，同網路的人有機會把檔案換掉，只在自己信任的網路用。
- **高安全性下載**：轉到 GitHub 發布頁，經 HTTPS 下載；請確認網址列是 `github.com/luna61ouo/padbridge`。

兩邊是同一個檔案，手機畫面會顯示 SHA-256，可以跟 `certutil -hashfile <檔名> SHA256` 對照。

**配對**：電腦圖示按右鍵 →「加一支手機（掃 QR）」→ 手機 App 切到 Wi-Fi →「選電腦」→「掃 QR 加電腦」。

### 藍牙模式與「小幫手」（進階，選用）

不裝小幫手也能用藍牙，但 Android App 只能用「會定期變換的隨機位址」，Windows 有時會連不回來。
小幫手用 adb 的身分在手機上跑，能用手機的固定位址、App 被關掉也不斷線，手機藍牙關開後也能由電腦端程式自動接回。

```bash
# 手機開啟「開發人員選項 → 無線偵錯」或接 USB，然後：
adb push daemon/start-daemon.sh /data/local/tmp/padbridge-start.sh
adb shell sh /data/local/tmp/padbridge-start.sh
```
再到 App 的「設定」打開「藍牙：固定位址（小幫手）」。手機重開機後要再跑一次。

### 安全設計

- 每支手機對每台電腦各有一把配對碼，**配對碼不會在網路上傳**：每個封包附上用配對碼算的簽章（HMAC-SHA256）與流水號，電腦只收簽章正確、流水號遞增的封包，同網路的人無法偽造或重播點擊。
- 配對碼透過「螢幕上的 QR → 手機相機」交給手機，不經過網路。
- 電腦防火牆只開 UDP 47801，只收同一個區網。
- 電腦端圖示可以隨時暫停，或撤銷某支手機。
- 仍會公開的：同網路的人看得到「有人在動滑鼠」（內容沒加密）；找電腦時電腦會回覆主機名稱。

### 從原始碼建置

需要 Android SDK（platform 35、build-tools 35.0.0）與 JDK 11 以上，不用 Gradle：

```bash
./build.sh          # 產出 build/padbridge.apk（沒有金鑰時會自動產一把 debug 用的）
./release.sh        # 產出 dist/ 裡的 APK 與 Windows 安裝包
```

### 目前限制

- 電腦端目前只支援 Windows；App 介面目前是中文
- 鍵盤、搖桿、手勢轉送還在規劃中

### 授權

Apache License 2.0，見 [LICENSE](LICENSE)。使用的第三方元件見 [NOTICE](NOTICE)。

---

## English

### What is it

Need a mouse but didn't bring one? Want to control your PC from the couch? Several people taking turns on one computer?
Padbridge turns an Android phone into a touchpad for your computer, over **Wi-Fi** or **Bluetooth LE**.

The mouse is just the first piece. Padbridge aims to be an **input relay**: the phone receives Bluetooth gamepads, keyboards, mice and watch gestures, and forwards them over Wi-Fi to computers that have no Bluetooth or are inconvenient to pair.

### What works today

| | Wi-Fi mode | Bluetooth LE mode |
|---|---|---|
| Install on the PC? | Yes (small Windows receiver with a tray icon) | No — the PC sees the phone as a Bluetooth mouse |
| One phone, several PCs | Yes, switch in the app | One at a time (tray icon can take it over in one click) |
| Several phones, one PC | Yes, each phone is paired separately | Yes |
| Best for | PCs without Bluetooth; coexisting with glasses/headsets | Anything that accepts a Bluetooth mouse (PC, tablet, VR) |

- Touchpad: one-finger move, tap = left click, two-finger tap = right click, two-finger scroll, press-and-hold then move to drag
- Pairing: the PC tray icon shows a QR code; scan it in the app
- Tray icon: lists connected phones (custom names), shows Wi-Fi/Bluetooth status, pause, revoke a phone

### Install

**Phone (Android 10+)**: download `padbridge-*.apk` from [Releases](../../releases).

**PC (Windows 10/11)**:
1. Download `PadbridgeSetup-windows-*.zip` from [Releases](../../releases) and extract it
2. Double-click `PadbridgeSetup.cmd`
   - "Windows protected your PC" → "More info" → "Run anyway" (the installer is not code-signed)
   - Allow the admin prompt (needed for the firewall rule)
3. The Padbridge tray icon appears; it starts automatically at every logon

The app can point you there too: "選電腦" (choose PC) → "電腦還沒裝 Padbridge？" (PC doesn't have Padbridge yet?) shows the Releases URL. If typing it is a pain, the PC's browser can open the phone's LAN address and choose:
- **Quick download**: the same setup zip served by the phone (it contains no pairing secret). Plain HTTP, so someone on the same network could swap the file — only use it on a network you trust.
- **High-security download**: redirects to the GitHub release page over HTTPS; check the address bar shows `github.com/luna61ouo/padbridge`.

Both are the same file; the phone shows its SHA-256 so you can compare with `certutil -hashfile <file> SHA256`.

**Pairing**: right-click the tray icon → "加一支手機（掃 QR）" (add a phone via QR) → in the app switch to Wi-Fi → "選電腦" (choose PC) → "掃 QR 加電腦" (scan QR).

### Bluetooth mode and the helper (advanced, optional)

Bluetooth works without the helper, but a normal Android app can only advertise with a rotating random address, so Windows sometimes fails to reconnect.
The helper runs on the phone with adb shell privileges: it uses the phone's fixed public address, keeps the mouse alive when the app is killed, and lets the PC receiver reconnect after the phone's Bluetooth is toggled.

```bash
# enable Developer options → Wireless debugging (or USB), then:
adb push daemon/start-daemon.sh /data/local/tmp/padbridge-start.sh
adb shell sh /data/local/tmp/padbridge-start.sh
```
Then enable "藍牙：固定位址（小幫手）" (Bluetooth: fixed address / helper) in the app settings. Re-run after the phone reboots.

### Security

- Each phone gets its own pairing key per PC. **The key never travels over the network**: every packet carries an HMAC-SHA256 signature and a strictly increasing counter, so others on the same network cannot forge or replay clicks.
- The key is handed over screen-to-camera via the QR code.
- The PC firewall opens only UDP 47801, local subnet only.
- Pause or revoke any phone from the tray icon.
- Still visible to others on the network: that someone is moving a mouse (payload is not encrypted), and the PC's hostname in discovery replies.

### Build from source

Requires the Android SDK (platform 35, build-tools 35.0.0) and JDK 11+. No Gradle:

```bash
./build.sh          # builds build/padbridge.apk (creates a debug key if none is configured)
./release.sh        # builds the APK and the Windows setup zip into dist/
```

### Limitations

- PC side is Windows-only for now; the app UI is in Chinese for now
- Keyboard, gamepad and gesture relaying are planned

### License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices in [NOTICE](NOTICE).
