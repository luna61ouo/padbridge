package io.github.luna61ouo.padbridge;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * 滑鼠 API（11-25）：讓同一支手機上的其他 App 操作滑鼠（送到目前選中的電腦）。說明見 repo 的 API.md。
 *
 * 連法：bindService(Intent("io.github.luna61ouo.padbridge.API").setPackage("io.github.luna61ouo.padbridge"))
 * 拿到 Messenger 後送 Message（what＝動作、arg1＝參數），附 replyTo 就會收到同 what、arg1＝狀態碼的回覆。
 *
 * 呼叫者是誰只看系統附上的 Message.sendingUid（App 改不了），不看任何 App 自己填的欄位。
 * 第一次（或沒登記）送 HELLO：Padbridge 發通知請使用者核准，回 PENDING；核准後才放行動作。
 */
public class ApiService extends Service {
    // 動作（what）
    public static final int MSG_VERSION = 0;   // 回 arg1＝API 版本，不需登記
    public static final int MSG_HELLO = 1;     // 要求登記／查狀態
    public static final int MSG_WHEEL = 2;     // arg1＝滾輪格數（正＝往上）
    public static final int MSG_CLICK = 3;     // arg1＝1 左鍵、2 右鍵
    // 狀態（回覆的 arg1）
    public static final int OK = 0, PENDING = 1, DENIED = 2, RATE_LIMITED = 3, NO_PC = 4, NOT_ALLOWED = 5, BAD_CALLER = 6, BAD_REQUEST = 7;
    public static final int API_VERSION = 1;

    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) { reply(m, handle(m)); }
        });
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private int handle(Message m) {
        if (m.what == MSG_VERSION) return API_VERSION;
        int uid = m.sendingUid;
        if (uid < 0 || uid == Process.myUid()) return BAD_CALLER;   // 同行程送進來的不會有 sendingUid
        ApiApps apps = ApiApps.get(this);
        ApiApps.Caller c = apps.resolve(uid);
        if (c == null) return BAD_CALLER;
        if (m.what == MSG_HELLO) {
            if (apps.isRegistered(c)) return OK;
            if (apps.isDenied(c.pkg)) return DENIED;
            apps.ask(c);
            return PENDING;
        }
        String scope;
        switch (m.what) {
            case MSG_WHEEL: scope = "scroll"; break;
            case MSG_CLICK: scope = "click"; break;
            default: return BAD_REQUEST;
        }
        if (!apps.isRegistered(c)) return apps.isDenied(c.pkg) ? DENIED : NOT_ALLOWED;
        if (!apps.allowed(c, scope)) return NOT_ALLOWED;
        if (!apps.takeToken(uid)) return RATE_LIMITED;
        MouseHub hub = MouseHub.get(this);
        if (hub.wifiHost().isEmpty()) return NO_PC;
        switch (m.what) {
            case MSG_WHEEL: {
                int n = Math.max(-10, Math.min(10, m.arg1));
                if (n == 0) return BAD_REQUEST;
                hub.setActive(true);
                hub.wheel(n);
                break;
            }
            case MSG_CLICK: {
                if (m.arg1 != MouseHub.BTN_LEFT && m.arg1 != MouseHub.BTN_RIGHT) return BAD_REQUEST;
                hub.setActive(true);
                hub.click(m.arg1);
                break;
            }
        }
        apps.touchActive(c);
        return OK;
    }

    private static void reply(Message m, int status) {
        if (m.replyTo == null) return;
        try { m.replyTo.send(Message.obtain(null, m.what, status, 0)); }
        catch (RemoteException e) { Log.w(ApiApps.TAG, "reply " + e); }
    }
}
