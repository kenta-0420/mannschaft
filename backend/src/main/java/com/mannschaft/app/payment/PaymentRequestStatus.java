package com.mannschaft.app.payment;

/**
 * 協会→加盟チーム請求（payment_requests）のステータス。
 *
 * <p>遷移:
 * {@code DRAFT}（発行・未配信）→ {@code SENT}（配信＝確認必須通知一斉送信）→
 * {@code VIEWED}（チームが閲覧）→ {@code PAID}（チーム ADMIN が支払い）。
 * {@code SENT}/{@code VIEWED} の期限超過は @Scheduled バッチが {@code OVERDUE} へ遷移する
 * （OVERDUE でも支払いは可能・下記）。{@code DRAFT}/{@code SENT} は {@code CANCELLED} へ取消可能。</p>
 *
 * <p><b>支払い可能な状態（02_api §7・本第一波で確定）:</b> {@code SENT}/{@code VIEWED}/{@code OVERDUE}。
 * 期限超過（OVERDUE）でも実運用上は支払えるべきため支払い可とする。
 * {@code DRAFT}（未配信）/{@code PAID}（支払い済）/{@code CANCELLED}（取消）からの支払いは不可。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.2 /
 * 02_api_design.md §7 / README §6.1。</p>
 */
public enum PaymentRequestStatus {

    /** 発行済み・未配信（協会 ADMIN が起票した直後）。 */
    DRAFT,

    /** 配信済み（確認必須通知をチーム ADMIN 群へ一斉送信した）。 */
    SENT,

    /** チームが請求を閲覧した。 */
    VIEWED,

    /** チーム ADMIN が支払い済み（money rail へ連結）。 */
    PAID,

    /** 支払期限を超過（SENT/VIEWED から @Scheduled バッチが遷移）。なお OVERDUE でも支払いは可能。 */
    OVERDUE,

    /** 発行者（協会 ADMIN）が取消した（DRAFT/SENT からのみ）。 */
    CANCELLED
}
