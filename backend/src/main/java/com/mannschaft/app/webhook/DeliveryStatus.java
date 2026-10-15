package com.mannschaft.app.webhook;

/**
 * Webhook配信ステータス。
 */
public enum DeliveryStatus {

    /** 配信成功 */
    SUCCESS,

    /** 配信失敗 */
    FAILED,

    /** リトライ中 */
    RETRYING
}
