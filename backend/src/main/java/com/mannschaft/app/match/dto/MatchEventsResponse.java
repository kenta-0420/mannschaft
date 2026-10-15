package com.mannschaft.app.match.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 試合内タイムライン取得のレスポンス DTO（02 §F.4）。
 *
 * <p>イベント一覧に加え、スコア整合警告フラグ（{@code scoreMismatch}）を含める（02 §E.5・F.4）。
 * 正本スコア（{@code matches.home/away_score}）と イベント導出得点（GOAL＋PENALTY_GOAL〔自サイド〕＋相手 OWN_GOAL・
 * PK 戦は対象外・soccer §4.2）が不一致のとき true。<b>自動で書き換えず</b>乖離を可視化する（症状を隠さない）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §E.5 / §F.4</p>
 */
@Getter
@Builder
public class MatchEventsResponse {

    private final List<MatchEventResponse> events;
    /** 正本スコアとイベント導出得点が不一致なら true（02 §E.5・握りつぶさない）。 */
    private final boolean scoreMismatch;
    /** イベントから導出したホーム本戦得点（参考表示用）。 */
    private final int derivedHomeScore;
    /** イベントから導出したアウェイ本戦得点（参考表示用）。 */
    private final int derivedAwayScore;
}
