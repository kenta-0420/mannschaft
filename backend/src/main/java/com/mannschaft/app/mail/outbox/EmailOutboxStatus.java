package com.mannschaft.app.mail.outbox;

/**
 * F09.18 メール配信 outbox の状態遷移定義。
 *
 * <p>設計書 §5 の状態遷移図に対応する 6 状態:</p>
 * <ul>
 *   <li>{@link #PENDING} — 送信待ち。Worker のポーリング対象</li>
 *   <li>{@link #SENDING} — Worker が取得済、SES 呼び出し中</li>
 *   <li>{@link #SENT} — SES 200 を返した最終成功状態</li>
 *   <li>{@link #DEAD_LETTER} — リトライ尽きた / 永久失敗種別</li>
 *   <li>{@link #FAILED} — 復号失敗 / テンプレ不在等のバリデーション失敗</li>
 *   <li>{@link #CANCELLED} — SYSTEM_ADMIN による手動キャンセル</li>
 * </ul>
 *
 * <p>DB カラムは VARCHAR(16) で保持し、本 enum との変換は Service 層が行う
 * (Flyway での ALTER 容易性確保のため、JPA Enumerated は使わない)。</p>
 */
public enum EmailOutboxStatus {
    PENDING,
    SENDING,
    SENT,
    DEAD_LETTER,
    FAILED,
    CANCELLED
}
