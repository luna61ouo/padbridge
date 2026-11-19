package io.github.luna61ouo.padbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * adb 測試入口（之後手錶轉送也走同一組動作）。只有 adb shell 能送（manifest 要求 DUMP 權限）。
 *   am broadcast -a io.github.luna61ouo.padbridge.CMD -p io.github.luna61ouo.padbridge --es op move --ei dx 100 --ei dy 0
 *   op: move(dx,dy) | wheel(n) | click(btn) | down(btn) | up(btn) | wifi(host,port,token) | pcs（找電腦並列出）| selpc(id) | rmpc(id) | flag(name,on)
 */
public class MouseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        MouseHub hub = MouseHub.get(c);
        String op = i.getStringExtra("op");
        if (op == null) return;
        int btn = i.getIntExtra("btn", MouseHub.BTN_LEFT);
        switch (op) {
            case "pcs": {   // 找一次電腦、列出清單（除錯用；discover 要 0.8 秒，放背景）
                final PendingResult pr = goAsync();
                new Thread(() -> {
                    hub.pcs().discover();
                    StringBuilder sb = new StringBuilder();
                    PcList.Pc sel = hub.pcs().selected();
                    for (PcList.Pc p : hub.pcs().sorted())
                        sb.append(sel != null && sel.id.equals(p.id) ? "*" : " ").append(p.online ? "on  " : "off ")
                          .append(p.id).append(' ').append(p.label()).append(' ').append(p.ip).append('\n');
                    pr.setResultData(sb.toString()); pr.finish();
                }).start();
                return;
            }
            case "selpc": hub.selectPc(i.getStringExtra("id")); break;
            case "rmpc": hub.pcs().remove(i.getStringExtra("id")); break;
            case "move": hub.move(i.getIntExtra("dx", 0), i.getIntExtra("dy", 0)); break;
            case "wheel": hub.wheel(i.getIntExtra("n", 0)); break;
            case "click": hub.click(btn); break;
            case "down": hub.button(btn, true); break;
            case "up": hub.button(btn, false); break;
            case "wifi": hub.setWifi(i.getStringExtra("host"), i.getIntExtra("port", 0), i.getStringExtra("token")); break;
            case "flag":   // 量測用：--es name noWifiLock --ez on true|false
                hub.setFlag(i.getStringExtra("name"), i.getBooleanExtra("on", false));
                break;
        }
        setResultData(hub.statusText());
    }
}
