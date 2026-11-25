package io.github.luna61ouo.padbridge;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import io.github.luna61ouo.padbridge.watch.WatchProto;

import java.nio.charset.StandardCharsets;

/** 手錶上打開 Padbridge 時送 HELLO 過來：手機 App 就算沒開也會被 Google 服務叫起來。 */
public class WatchListener extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent e) {
        if (!WatchProto.PATH_HELLO.equals(e.getPath())) return;
        String node = e.getSourceNodeId();
        String why = null;
        if (!MouseHub.get(this).settings().on(MouseSettings.WATCH)) why = "手機上還沒打開「手錶滑鼠」（Padbridge 設定）";
        else if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            why = "手機上的 Padbridge 還沒有「附近的裝置」權限";
        if (why != null) {
            Log.i(MouseHub.TAG, "watch hello refused: " + why);
            Wearable.getMessageClient(this).sendMessage(node, WatchProto.PATH_READY, ("err|" + why).getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            startForegroundService(new Intent(this, WatchLinkService.class).putExtra("node", node));
        } catch (Throwable t) {
            Log.w(MouseHub.TAG, "watch link service blocked", t);
            Wearable.getMessageClient(this).sendMessage(node, WatchProto.PATH_READY,
                    ("err|手機不讓 Padbridge 在背景開服務，請在手機上打開 Padbridge 一次").getBytes(StandardCharsets.UTF_8));
        }
    }
}
