package io.github.luna61ouo.padbridge;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * 被控端的自我檢查（11-25）：這台設定成被控端（有配對碼）但「Padbridge 被控端」無障礙服務沒開時，
 * 跳一則通知說清楚、點了直接到無障礙設定。不然別台裝置只會看到「連不到」，這台自己什麼都不說。
 *
 * 什麼時候檢查：開機後（有些車機、ROM 開機時會把第三方無障礙服務關掉）、App 更新後，
 * 以及開機 3 分鐘後再看一次（系統可能晚一點才清）。服務一連上就把通知收掉（RemoteService.onServiceConnected）。
 * 沒設定成被控端的裝置（沒有配對碼）什麼都不做，一般使用者不會看到。
 */
public class RemoteWatch extends BroadcastReceiver {
    static final String RECHECK = "io.github.luna61ouo.padbridge.REMOTE_RECHECK";
    static final String CH = "remote_off";
    static final int NOTI_ID = 4711;

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        check(c);
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(RECHECK).setClass(c, RemoteWatch.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (am != null) am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 3 * 60_000L, pi);
        }
    }

    /** 這台有沒有被設定成被控端（有任何一支手機的配對碼）。 */
    static boolean configured(Context c) {
        String t = c.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("tokens", "{}");
        return t != null && !t.replaceAll("\\s", "").equals("{}");
    }

    /** 系統設定裡「Padbridge 被控端」有沒有開。 */
    static boolean serviceEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(c, RemoteService.class);
        for (String s : list.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s.trim());
            if (me.equals(cn)) return true;
        }
        return false;
    }

    static void check(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (!configured(c) || serviceEnabled(c)) { nm.cancel(NOTI_ID); return; }
        Log.w(RemoteService.TAG, "被控端已設定但無障礙服務沒開 ⇒ 通知");
        nm.createNotificationChannel(new NotificationChannel(CH, "被控端沒開", NotificationManager.IMPORTANCE_HIGH));
        Intent go = new Intent(c, RemoteSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // 設定畫面有「去打開」＋灰色開關的解法
        PendingIntent pi = PendingIntent.getActivity(c, 1, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = "別台裝置現在控制不了這台。點這裡照步驟把「" + c.getString(R.string.remote_label) + "」打開。";
        nm.notify(NOTI_ID, new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Padbridge 被控端沒有開")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    static void clear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTI_ID);
    }
}
