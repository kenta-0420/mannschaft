package com.mannschaft.app.billing.beta;

/**
 * F20.3 ベータ特典の取消事由（{@code beta_grants.revoke_reason}・VARCHAR(64)）。
 *
 * <p>{@code revoked_at} とセットで必須（アプリ層保証・設計書 01 §1）。取消は終端で復活しない。</p>
 */
public enum BetaRevokeReason {

    /** 規約違反による取消（シスアド操作）。 */
    TERMS_VIOLATION,

    /** アカウント譲渡が確認された取消（シスアド操作）。 */
    ACCOUNT_TRANSFER,

    /**
     * 退会確定（物理削除）に伴うシステム取消（設計書 02 §5.1・{@code AccountPurgedEvent} 受信時）。
     * <p>API からは指定不可のシステム専用値（設計書 02 §4.2）。{@code revoked_by=NULL}。</p>
     */
    WITHDRAWAL,

    /** その他（監査メモ併記を推奨）。 */
    OTHER
}
