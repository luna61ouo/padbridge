# Padbridge 滑鼠 API（v1）

同一支手機上的其他 App 可以透過這個 API 操作滑鼠，動作會送到 Padbridge 目前選中的電腦。

## 連線

```xml
<!-- 呼叫端 manifest：Android 11 起要宣告看得到 Padbridge -->
<queries><package android:name="io.github.luna61ouo.padbridge" /></queries>
```

```java
Intent i = new Intent("io.github.luna61ouo.padbridge.API").setPackage("io.github.luna61ouo.padbridge");
context.bindService(i, conn, Context.BIND_AUTO_CREATE);
// onServiceConnected：Messenger tx = new Messenger(binder);
Message m = Message.obtain(null, what, arg1, 0);
m.replyTo = myMessenger;     // 要回覆就附上；回覆的 what 同送出的，arg1＝狀態碼
tx.send(m);
```

## 動作（what）

| what | 名稱 | arg1 | 需要的權限 |
|---|---|---|---|
| 0 | VERSION | — | 不用登記，回覆的 arg1＝API 版本（目前 1） |
| 1 | HELLO | — | 要求登記／查狀態 |
| 2 | WHEEL | 滾輪格數，正＝往上，-10～10 | scroll |
| 3 | CLICK | 1＝左鍵、2＝右鍵 | click |

## 狀態碼（回覆的 arg1）

| 值 | 意思 |
|---|---|
| 0 | OK |
| 1 | PENDING：已發通知請使用者核准 |
| 2 | DENIED：使用者拒絕過（要在 Padbridge 設定裡移除後才會再問） |
| 3 | RATE_LIMITED：每秒最多 30 個動作 |
| 4 | NO_PC：還沒選電腦 |
| 5 | NOT_ALLOWED：還沒核准，或簽章跟核准時不同 |
| 6 | BAD_CALLER：認不出呼叫者（共用 uid 等） |
| 7 | BAD_REQUEST：動作或參數不對 |

## 安全

- 呼叫者是誰只看系統附上的 uid（`Message.sendingUid`），App 自己填的資料不算數。
- 核准時記下呼叫端的套件名稱＋簽章指紋（SHA-256）。同名但換了簽章＝陌生人，要重新核准。
  核准的那一刻會再算一次簽章，跟發出請求時不同就不收。
- 核准只能在 Padbridge 自己的畫面按：那個畫面不對外開放、會隱藏其他 App 疊在上面的視窗、被遮住時的點擊不算、按鈕要等 1 秒才能按。
- 有 App 在操作時，通知列常駐「某某正在控制滑鼠」＋撤銷鍵；設定頁可以看名單、移除。
- v1 只給捲動和左右鍵，不能移動游標、不能打字。
- 擋不住：已經拿到無障礙權限的 App 可以替使用者按「允許」（Android 本身的限制）。

## 測試

`probe/api-client/`：測試用的呼叫端（只收 adb 廣播）。`build.sh [金鑰]` 不同金鑰＝模擬偽造。
MIUI：新 App 要先打開一次（`am start -n io.github.luna61ouo.pbapitest/.Main`）才收得到廣播。
