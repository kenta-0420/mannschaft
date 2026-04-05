package com.mannschaft.app.queue;

/**
 * チケット発行元。チケットがどの方法で発行されたかを表す。
 */
public enum TicketSource {
    /** QRコードからの発券 */
    QR,
    /** オンラインからの発券 */
    ONLINE,
    /** 管理者による手動発券 */
    ADMIN
}
