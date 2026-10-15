package com.mannschaft.app.notification.fanout;

/**
 * 通知 fan-out 耐久ジョブ（{@link NotificationFanoutJob}）のライフサイクル状態（P2）。
 *
 * <ul>
 *   <li>{@link #PENDING} — enqueue 済み・未処理（または再開待ち）。ワーカーの取得対象。</li>
 *   <li>{@link #RUNNING} — ワーカーが処理中（クラッシュ時は stuck リカバリで PENDING へ戻す）。</li>
 *   <li>{@link #DONE} — 全受信者への fan-out が完了。</li>
 *   <li>{@link #FAILED} — 一時失敗（リトライ待ち・{@code next_attempt_at} にバックオフ）。</li>
 *   <li>{@link #DEAD_LETTER} — リトライ上限超で恒久失敗。行は消さず調査・手動再投入の対象として残す（AC-3）。</li>
 * </ul>
 */
public enum NotificationFanoutJobStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DEAD_LETTER
}
