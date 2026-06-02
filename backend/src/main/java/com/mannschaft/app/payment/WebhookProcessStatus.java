package com.mannschaft.app.payment;

/**
 * F22.1 謝礼決済: Webhook イベントの処理状態。
 *
 * <p>{@code stripe_webhook_events.process_status}（VARCHAR(12) + CHECK）に対応する。</p>
 */
public enum WebhookProcessStatus {
    /** 受信のみ・処理未完（再試行対象）。 */
    RECEIVED,
    /** 処理完了。 */
    PROCESSED,
    /** 処理対象外として無視。 */
    IGNORED,
    /** 処理失敗。 */
    FAILED
}
