package com.mannschaft.app.match.domain;

/**
 * イベント／出場のチームサイド（F08.10 コア・競技非依存）。
 *
 * <p>{@code match_events.team_side} / {@code player_appearances.team_side}（ENUM('HOME','AWAY')）に格納される。
 * 「どちらのチームのイベント／選手か」を表す 2 値である。</p>
 *
 * <p>中立地（{@code matches.home_away=NEUTRAL}）でも主体チームは物理的に HOME 側 {@code team_side} に
 * 割り当てる（集計でホーム/アウェイ別成績に混入させず別カテゴリで扱う・01 §未解決 4）。
 * すなわち NEUTRAL は試合レベルの属性（{@code matches.home_away}）であり、
 * イベント／出場レベルの {@code team_side} は HOME/AWAY の 2 値に閉じる。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1 / §未解決 4</p>
 */
public enum TeamSide {
    HOME,
    AWAY
}
