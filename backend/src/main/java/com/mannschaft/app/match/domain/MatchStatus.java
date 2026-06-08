package com.mannschaft.app.match.domain;

/**
 * 試合の進行状態（F08.10 コア・競技非依存）。
 *
 * <p>{@code matches.status}（VARCHAR・{@code @Enumerated(STRING)}・既定 'SCHEDULED'）に格納される。</p>
 *
 * <p>既存 {@code com.mannschaft.app.tournament.MatchStatus} と<b>値域を一致</b>させる（5 値・POSTPONED 含む）。
 * fixture 化（Phase 5）で tournament status を match 側へ寄せるため、両者を一致させておく。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1 / §B.1.1 照合表</p>
 */
public enum MatchStatus {
    /** 予定 */
    SCHEDULED,
    /** 進行中 */
    IN_PROGRESS,
    /** 終了（確定再計算・順位導出トリガー） */
    COMPLETED,
    /** 延期（再日程待ち・順位導出対象外） */
    POSTPONED,
    /** 中止（順位導出対象外） */
    CANCELLED
}
