package com.mannschaft.app.onboarding.dto;

/**
 * リマインダー送信レスポンス。
 *
 * @param remindedCount リマインド<b>対象者数</b>。Issue #2834 / CMP-056 の非同期化（AFTER_COMMIT +
 *                      {@code event-pool}）により、レスポンス返却時点では送信成功数を確定できなくなったため、
 *                      送信成功数ではなく対象者数を返す（常に {@code totalInProgress} と同値）。
 *                      外向き契約を壊さないためフィールド自体は維持する。
 * @param totalInProgress 進行中のオンボーディング件数（= リマインド対象者数）
 */
public record RemindResponse(
        Integer remindedCount,
        Integer totalInProgress
) {}
