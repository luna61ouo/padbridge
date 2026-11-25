package io.github.luna61ouo.padbridge.watch;

import java.util.UUID;

/**
 * 手錶 ⇄ 手機 的約定（兩邊共用這一份）。
 *
 * 流程：手錶打開 → 用 Google 手錶通訊（Wearable Data Layer）送 HELLO 叫醒手機
 *      → 手機開前景服務、聽藍牙直連（RFCOMM），用 READY 回「這次的通關碼｜手機藍牙名稱｜選中的電腦」
 *      → 手錶用藍牙直連連上，先送通關碼，之後每 10 ms 左右送一個「狀態框」，手機每框回一個收到。
 *
 * 為什麼游標不走 Google 那條：實測來回 120～140 ms，藍牙直連約 20～40 ms（WATCH-PLAN 第 0 步）。
 * Google 那條只負責叫醒和交換通關碼——它只送得到同一把金鑰簽的 App，所以別的 App 拿不到通關碼。
 */
public final class WatchProto {
    private WatchProto() { }

    public static final int VERSION = 1;
    /** 傳統藍牙（備援）。實測會「一坨一坨到」（約每 40 ms 交貨一次），游標不順。 */
    public static final UUID RFCOMM_UUID = UUID.fromString("70616462-7269-6467-6500-77617463680a");
    /** BLE（優先）：手機被叫醒後用這個服務 UUID 廣播，手錶掃到就連、要求高速檔，再開 L2CAP 通道。
     *  實測框到手機的間隔中位 12 ms、p90 17 ms（傳統藍牙 p90 約 38 ms）。 */
    public static final UUID ADV_UUID = UUID.fromString("70616462-7269-6467-6501-77617463680a");

    public static final String PATH_PREFIX = "/padbridge/watch";
    /** 手錶 → 手機：叫醒。內容：協定版本（1 位元組）。 */
    public static final String PATH_HELLO = PATH_PREFIX + "/hello";
    /** 手機 → 手錶：準備好了。內容：UTF-8「ok|通關碼hex|手機藍牙名稱|電腦名稱|BLE 通道號（PSM，-1＝沒開）」或「err|原因」。 */
    public static final String PATH_READY = PATH_PREFIX + "/ready";

    /** 藍牙直連第一個框：'H' ＋ 16 位元組通關碼。 */
    public static final byte HELLO = 'H';
    public static final int TOKEN_LEN = 16;

    /**
     * 手錶 → 手機 狀態框，固定 10 位元組：'F'、序號 u16、dx i16、dy i16、滾輪 i8、按鍵狀態 u8、旗標 u8。
     * 按鍵送「現在按著哪些」（狀態，不是事件）：掉了下一框就更正，斷線時手機一律全部放開。
     * dx／dy 是手錶畫面上的像素位移，倍率在手機端（設定都在手機）。
     */
    public static final byte FRAME = 'F';
    public static final int FRAME_LEN = 10;
    public static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 4;

    /** 手機 → 手錶：'A' ＋ 序號 u16（收到）；'S' ＋ 長度 u8 ＋ UTF-8（狀態文字，例如電腦名稱、沒連上）。 */
    public static final byte ACK = 'A', STATUS = 'S';

    /**
     * 選電腦（11-23，手錶選單）：
     * 手錶 → 手機：'L'（要電腦清單）；'P' ＋ 長度 u8 ＋ UTF-8 pcId（選這台）。
     * 手機 → 手錶：'C' ＋ 長度 u16 ＋ UTF-8 清單，一行一台「pcId \t 名稱 \t 在線 1/0 \t 選中 1/0」。
     * 清單只有名稱和代號，配對碼、IP 不離開手機。
     */
    public static final byte LIST_REQ = 'L', PICK = 'P', LIST = 'C';

    /** 路上最多幾框沒收到回音（實測 8 框：穩定時事件到手機中位約 20 ms，壞的時候最多多排 8 框）。 */
    public static final int WINDOW = 8;
    /** 送框的節奏（毫秒）。手指在板上時沒有位移也送空框，讓藍牙連線保持清醒。 */
    public static final int TICK_MS = 10;
}
