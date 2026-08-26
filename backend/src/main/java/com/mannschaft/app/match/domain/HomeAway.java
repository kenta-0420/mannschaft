package com.mannschaft.app.match.domain;

/**
 * 試合の主体チームのホーム/アウェイ/中立地（F08.10 コア・競技非依存）。
 *
 * <p>{@code matches.home_away}（ENUM('HOME','AWAY','NEUTRAL')・既定 'HOME'）に格納される。
 * 試合レベルの属性であり、イベント／出場レベルの {@link TeamSide}（HOME/AWAY 2 値）とは別概念である
 * （中立地でも team_side は HOME/AWAY に割り当てる・01 §未解決 4）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1 / §未解決 4</p>
 */
public enum HomeAway {
    HOME,
    AWAY,
    NEUTRAL
}
