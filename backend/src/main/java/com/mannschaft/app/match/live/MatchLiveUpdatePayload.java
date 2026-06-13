package com.mannschaft.app.match.live;

import com.mannschaft.app.match.domain.MatchStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * F08.10 / 07 §J.2.1 STOMP トピック {@code /topic/matches/{matchId}/live} へ配信する差分ペイロード。
 *
 * <p><b>差分配信（帯域節約）</b>: 追加/更新/削除されたイベント 1 件＋更新後スコアサマリ／ステータスのみを送る
 * （全件再送しない）。観戦者は本差分でタイムライン/スコアを部分更新する（07 §J.2.1）。</p>
 *
 * <p><b>serverSeq（単調増加）</b>: 配信順序の検出に用いる。観戦者は seq の飛びを検知したらスナップショット
 * 再取得で整合を回復する（07 §J.2.1 / §J.4）。順序保証のない broker でも観戦者が整合を回復できる。</p>
 *
 * <p><b>機微情報の除外（07 §J.3.3 二重防御）</b>: ペイロードには「公開可能な試合進行情報」のみを載せる。
 * イベントは {@link MatchLiveEventView}（内部ユーザー ID・recorded_by_team_id を除外）を用い、スコアは
 * {@link MatchLiveScoreSummary}（所有チーム ID 等を含まない）を用いる。万一購読認可（07 §J.3.1）を抜けても
 * 漏れるのは公開情報に限られる。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2.1 / §J.3.3</p>
 */
@Getter
@Builder
public class MatchLiveUpdatePayload {

    /** メッセージ種別（07 §J.2.1）。 */
    private final MatchLiveUpdateType type;

    /** 対象試合 ID（UUIDv7）。 */
    private final UUID matchId;

    /** 配信シーケンス（単調増加・順序検出・07 §J.2.1）。 */
    private final long serverSeq;

    /** 差分イベント（EVENT_ADDED/UPDATED 時。DELETED は {@link #eventId} のみ）。 */
    private final MatchLiveEventView event;

    /** 削除イベント ID（EVENT_DELETED 時のみ）。 */
    private final UUID eventId;

    /** 更新後スコアサマリ（SCORE_UPDATED 時。他種別では NULL 可）。 */
    private final MatchLiveScoreSummary score;

    /** 遷移後ステータス（STATUS_CHANGED 時）。 */
    private final MatchStatus status;
}
