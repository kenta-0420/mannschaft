package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.TeamSide;

/**
 * 1 試合・1 選手分の基本スコアリング集計（得点/アシスト）を表す不変 DTO（F08.10 05 §H.2.2）。
 *
 * <p><b>ドメイン越境の橋渡し</b>: 個人ランキングの基本スタッツ（得点王/アシスト王）の源泉を
 * {@code match_events}（GOAL/ASSIST・本戦のみ・PK 戦除外）に正本化するため、match ドメインが
 * 当該試合の選手別集計を本 DTO（プレーン値のみ）で公開し、tournament ドメインがそれを受けて
 * fixture スナップショット（{@code tournament_match_player_stats}）へ同期する。
 * Entity を渡さずプレーン DTO（{@code playerUserId}/{@code teamSide}/{@code goals}/{@code assists}）
 * のみを越境させることで、{@code CrossDomainEntityImportArchTest}（CLAUDE.md 原則 1・
 * tournament→match の entity 直接 import 禁止）に抵触しない。</p>
 *
 * <p><b>集計定義の一元化</b>: 得点は {@code GOAL + PENALTY_GOAL}（PK 戦 {@code PENALTY_SHOOTOUT} は除外）、
 * アシストは {@code ASSIST}。{@code MatchStatsAggregationService} の既存集計と同一定義であり、
 * アプリ全体で得点/アシストの数え方が一致する（sports/01_soccer.md §6.1）。</p>
 *
 * @param playerUserId 主体選手の user_id（{@code match_events.player_user_id}・未登録選手は集計対象外ゆえ非 null）
 * @param teamSide     イベントのチームサイド（HOME/AWAY・fixture の participant 引当に用いる）
 * @param goals        得点数（GOAL + PENALTY_GOAL）
 * @param assists      アシスト数（ASSIST）
 */
public record MatchScoringTally(
        Long playerUserId,
        TeamSide teamSide,
        int goals,
        int assists) {
}
