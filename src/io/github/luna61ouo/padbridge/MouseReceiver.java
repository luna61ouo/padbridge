package io.github.luna61ouo.padbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * adb 測試入口（之後手錶轉送也走同一組動作）。只有 adb shell 能送（manifest 要求 DUMP 權限）。
 *   am broadcast -a io.github.luna61ouo.padbridge.CMD -p io.github.luna61ouo.padbridge --es op move --ei dx 100 --ei dy 0
 *   op: token（回暗號）| daemon(on) | move(dx,dy) | wheel(n) | click(btn) | down(btn) | up(btn) | wifi(host,port,token) | mode(m: ble|wifi) | pcs（找電腦並列出）| selpc(id)
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
                          .append(p.id).append(' ').append(p.label()).append(' ').append(p.ip).append(" bt=").append(p.bt).append('\n');
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
            case "flag":   // 量測用：--es name noSmooth|noYield --ez on true|false
                hub.setFlag(i.getStringExtra("name"), i.getBooleanExtra("on", false));
                break;
            case "blehost":   // 指定（或更正）BLE 電腦位址，重啟 BLE 讓它重新接上
                c.getSharedPreferences("mouse", Context.MODE_PRIVATE).edit().putString("bleHost", i.getStringExtra("addr")).commit();
                hub.stopBle(); hub.startBle();
                break;
            case "token":   // 小幫手啟動腳本（shell 身分）來拿暗號
                setResultData(hub.daemonToken());
                return;
            case "daemonquit":   // 啟動腳本換版本前先叫舊的小幫手好好退場
                hub.daemonQuit();
                break;
            case "daemon": {   // --ez on true：改由小幫手當滑鼠本體（App 只轉送）；false：回到 App 自己來
                hub.setFlag("daemon", i.getBooleanExtra("on", false));
                if (hub.mode() == MouseHub.MODE_BLE) { hub.stopBle(); hub.startBle(); }
                break;
            }
            case "mode": {
                String m = i.getStringExtra("m");
                int nm = "wifi".equals(m) ? MouseHub.MODE_WIFI : MouseHub.MODE_BLE;   // 傳統藍牙已移除，bt 也當 ble
                hub.setMode(nm);
                if (nm == MouseHub.MODE_BLE) hub.startBle(); else hub.stopBle();
                break;
            }
        }
        setResultData(hub.statusText());
    }
}
