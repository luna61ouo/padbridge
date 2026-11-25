package io.github.luna61ouo.padbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** 「正在控制滑鼠」通知上的「撤銷」鍵。不對外開放，只有 Padbridge 自己的通知叫得到。 */
public class ApiRevokeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String pkg = i.getStringExtra("pkg");
        if (pkg == null) return;
        ApiApps a = ApiApps.get(c);
        a.remove(pkg);
        a.forgetCaller(pkg);
        a.clearActive();
        Toast.makeText(c, "已撤銷，它不能再控制滑鼠", Toast.LENGTH_SHORT).show();
    }
}
