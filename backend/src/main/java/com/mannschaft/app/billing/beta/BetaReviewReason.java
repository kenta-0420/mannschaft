package com.mannschaft.app.billing.beta;

/**
 * F20.3 ベータ特典の審査フラグ事由（{@code beta_grants.review_reason}・VARCHAR(32)）。
 *
 * <p>{@code review_flag=true} のとき必須（アプリ層保証・設計書 01 §1）。フラグが立っても権利は有効のまま
 * （運営審査待ちの表示に用いる）。設計書 01 §4.2 の状態遷移に対応する。</p>
 */
public enum BetaReviewReason {

    /**
     * オーナー変更イベント起点（自動）。
     * <p><b>Phase 2 保留（マスター 2026-07-08）</b>: 自動イベント購読（B-4）は初期スコープ外。
     * 初期スコープではシスアド手動 {@link #MANUAL} で代替する。値は Phase 2 でそのまま使うため温存する。</p>
     */
    OWNER_CHANGED,

    /** 譲渡疑い（将来の検知拡張用の予約値・自動）。 */
    SUSPECTED_TRANSFER,

    /** シスアドの手動フラグ（初期スコープの正規経路）。 */
    MANUAL
}
