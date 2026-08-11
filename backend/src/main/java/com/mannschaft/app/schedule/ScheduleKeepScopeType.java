package com.mannschaft.app.schedule;

/**
 * キープ（日付未定の予定）が属するスコープの種別（F03.17 §2.2 / §2.3）。
 *
 * <p>{@code schedule_keeps} は {@code team_id} / {@code organization_id} / {@code user_id} の
 * いずれか 1 列だけが非 NULL であることを DB CHECK（{@code ck_schedule_keeps_scope_xor}）で
 * 保証しており、本 enum はその 3 スコープを型として表現する。</p>
 *
 * <p><strong>用途の限定</strong>: 本 enum はキープ機能内部（可視性判定・認可ゲート）専用であり、
 * メンバーシップ系 API が受け取る汎用 scopeType 文字列とは別物である。
 * {@link #membershipScopeType()} で必要なときだけ文字列表現へ変換する
 * （{@code PERSONAL} はメンバーシップ概念を持たないため変換不可）。</p>
 */
public enum ScheduleKeepScopeType {

    /** チームスコープ（{@code team_id} 非 NULL）。本機能の主役。 */
    TEAM,

    /** 組織スコープ（{@code organization_id} 非 NULL）。 */
    ORGANIZATION,

    /** 個人スコープ（{@code user_id} 非 NULL）。所有者本人のみが見える。 */
    PERSONAL;

    /**
     * {@code AccessControlService} 等が受け取るメンバーシップ scopeType 文字列へ変換する。
     *
     * @return {@code "TEAM"} または {@code "ORGANIZATION"}
     * @throws IllegalStateException {@link #PERSONAL} の場合（メンバーシップ概念が存在しない）
     */
    public String membershipScopeType() {
        return switch (this) {
            case TEAM -> "TEAM";
            case ORGANIZATION -> "ORGANIZATION";
            case PERSONAL -> throw new IllegalStateException(
                    "PERSONAL スコープはメンバーシップ scopeType を持たない（所有者判定で扱うこと）");
        };
    }
}
