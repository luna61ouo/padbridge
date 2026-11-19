package io.github.luna61ouo.padbridge;

/**
 * Wi-Fi 封包的封裝：配對碼不上線，每包帶「流水號＋簽章」。
 *   v2|流水號|指令|參數…|簽章
 * 簽章＝HMAC-SHA256(配對碼, 簽章前面整段) 前 16 字。流水號＝現在時間（微秒級，嚴格遞增）。
 * 電腦端只收簽章對、流水號比上一包大、時間跟電腦差不到 10 分鐘的封包：同網路的人看得到你在動滑鼠，
 * 但偽造不了、也沒辦法把錄下來的點擊再送一次。
 */
final class Wire {
    private static long last;
    static synchronized long nextCounter() {
        long c = System.currentTimeMillis() * 1000;
        if (c <= last) c = last + 1;
        last = c;
        return c;
    }
    static String seal(String token, String body) {
        String s = "v2|" + nextCounter() + "|" + body;
        return s + "|" + PcList.hmac(token, s);
    }
}
