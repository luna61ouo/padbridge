package io.github.luna61ouo.padbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** 前景服務：讓藍牙滑鼠在關掉畫面、切到別的 App 時也不斷線。 */
public class MouseService extends Service {
    static final String CH = "mouse";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Padbridge", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Padbridge 開著")
                .setContentText("點這裡回到觸控板")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        MouseHub hub = MouseHub.get(this);
        if (hub.mode() == MouseHub.MODE_BLE) hub.startBle();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        MouseHub.get(this).stopBle();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
