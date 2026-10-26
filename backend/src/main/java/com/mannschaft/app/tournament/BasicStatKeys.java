package com.mannschaft.app.tournament;

import java.util.List;

/**
 * 個人ランキングの<b>基本スタッツ</b>（match_events 由来に正本化される項目）の statKey 定数
 * （F08.10 05 §H.2.2 / §H.6）。
 *
 * <p>これらの statKey の選手スタッツ（{@code tournament_match_player_stats}）は、
 * 試合完了イベント（{@code MatchCompletedEvent}）受信時に match ドメインの集計
 * （{@code match_events} の GOAL/ASSIST）から fixture スナップショットへ同期される
 * （{@code MatchScoreFixtureListener}）。大会主催者が任意定義する<b>大会固有の独自 statKey</b>
 * （H.6・独自 MVP ポイント等）は本集合に含まれず、従来どおり tournament 側 EAV に残置される
 * （手入力 {@code FixtureService.updatePlayerStats} で書かれた値を保持）。</p>
 *
 * <p><b>statKey 慣行</b>: 得点 = {@code "goals"}、アシスト = {@code "assists"}（既存テスト・seed の慣行）。
 * 大会の {@code tournament_stat_defs} にこれらの statKey が {@code isRankingTarget=true} で定義されていれば
 * 得点王/アシスト王として順位算出される。定義が無い場合スナップショット行は無害に残るのみ
 * （{@code RankingsCalculationService} は ranking 対象 def のみ走査するため順位には現れない）。</p>
 */
public final class BasicStatKeys {

    /** 得点（GOAL + PENALTY_GOAL）。 */
    public static final String GOALS = "goals";

    /** アシスト（ASSIST）。 */
    public static final String ASSISTS = "assists";

    /** match_events 由来に正本化される基本 statKey の集合（snapshot 同期時の delete 対象）。 */
    public static final List<String> ALL = List.of(GOALS, ASSISTS);

    private BasicStatKeys() {
    }
}
