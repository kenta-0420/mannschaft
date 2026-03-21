package com.mannschaft.app.queue;

/**
 * 受付モード。カウンターが受け付けるチケット発行方法を表す。
 */
public enum AcceptMode {
    /** QRコードからの発券のみ */
    QR_ONLY,
    /** オンラインからの発券のみ */
    ONLINE_ONLY,
    /** QRコード・オンライン両方 */
    BOTH
}
