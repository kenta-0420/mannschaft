package com.mannschaft.app.auth;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意リンクのステータス区分。
 *
 * <ul>
 *   <li>{@link #PENDING}  — 保護者の応答を待機中（トークン発行直後）</li>
 *   <li>{@link #APPROVED} — 保護者が同意を承認済み</li>
 *   <li>{@link #REJECTED} — 保護者が同意を拒否</li>
 *   <li>{@link #REVOKED}  — 管理者または保護者が同意を取り消し</li>
 * </ul>
 */
public enum ParentalConsentLinkStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REVOKED
}
