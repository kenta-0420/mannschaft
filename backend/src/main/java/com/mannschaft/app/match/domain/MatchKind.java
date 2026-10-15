package com.mannschaft.app.match.domain;

/**
 * 試合種別（F08.10 コア・競技非依存）。
 *
 * <p>{@code matches.kind}（VARCHAR・{@code @Enumerated(STRING)}）に格納される。
 * 全試合（練習・親善・大会・リーグ）を単一テーブル {@code matches} で保持するための区別。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1</p>
 */
public enum MatchKind {
    /** 練習試合 */
    PRACTICE,
    /** 親善試合 */
    FRIENDLY,
    /** 大会試合（tournament fixture リンク） */
    TOURNAMENT,
    /** リーグ戦 */
    LEAGUE
}
