package com.mannschaft.app.match.live;

import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

/**
 * F08.10 / 07 §J.2 記録経路（HTTP 正本）のコミット後に、配信リスナーへ「ライブ更新が起きた」ことを伝える
 * アプリ内イベント。
 *
 * <p><b>固有名（命名衝突回避）</b>: 既存 {@code MatchCompletedEvent}（順位連携・05）や tournament ドメインの
 * Match* と衝突しないよう {@code match.live} パッケージ配下の固有名とする（feedback: 別パッケージ同名 Bean は
 * ApplicationContext 全滅）。</p>
 *
 * <p><b>正本は HTTP・本イベントは配信のトリガーのみ（07 §J.1）</b>: 記録/更新/削除/スコア確定/ステータス遷移の
 * 各コミット経路で {@code ApplicationEventPublisher} に publish される。受信は
 * {@link MatchLiveBroadcastListener}（AFTER_COMMIT・配信専用・DB 書き込みなし）。</p>
 *
 * <p><b>機微情報の非搬送</b>: 本イベントの {@code event} には {@link MatchLiveEventView#from} で機微情報を除いた
 * 最小ビューのみを載せる（07 §J.3.3）。{@code serverSeq} は配信時点で
 * {@link MatchLiveBroadcastListener} が単調増加採番する（記録スレッドでは採番しない＝配信順序を一元化）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2</p>
 */
@Getter
@Builder
@ToString
public class MatchLiveUpdateEvent {

    /** 対象試合 ID（UUIDv7）。 */
    private final UUID matchId;

    /** 更新種別（07 §J.2.1）。 */
    private final MatchLiveUpdateType type;

    /** 差分イベントの最小ビュー（EVENT_ADDED/UPDATED 時・機微情報除外済み・NULL 可）。 */
    private final MatchLiveEventView event;

    /** 削除イベント ID（EVENT_DELETED 時・NULL 可）。 */
    private final UUID eventId;

    /** 更新後スコアサマリ（SCORE_UPDATED 時・NULL 可）。 */
    private final MatchLiveScoreSummary score;

    /** 遷移後ステータス（STATUS_CHANGED 時・NULL 可）。 */
    private final MatchStatus status;

    // ─────────────────────────────────────────────
    // ファクトリ（記録経路から最小情報で組み立てる）
    // ─────────────────────────────────────────────

    /** タイムラインイベント追加（07 §J.2.1 EVENT_ADDED）。 */
    public static MatchLiveUpdateEvent eventAdded(UUID matchId, MatchEventEntity event) {
        return MatchLiveUpdateEvent.builder()
                .matchId(matchId)
                .type(MatchLiveUpdateType.EVENT_ADDED)
                .event(MatchLiveEventView.from(event))
                .build();
    }

    /** タイムラインイベント更新（07 §J.2.1 EVENT_UPDATED）。 */
    public static MatchLiveUpdateEvent eventUpdated(UUID matchId, MatchEventEntity event) {
        return MatchLiveUpdateEvent.builder()
                .matchId(matchId)
                .type(MatchLiveUpdateType.EVENT_UPDATED)
                .event(MatchLiveEventView.from(event))
                .build();
    }

    /** タイムラインイベント削除（07 §J.2.1 EVENT_DELETED・ID のみ）。 */
    public static MatchLiveUpdateEvent eventDeleted(UUID matchId, UUID eventId) {
        return MatchLiveUpdateEvent.builder()
                .matchId(matchId)
                .type(MatchLiveUpdateType.EVENT_DELETED)
                .eventId(eventId)
                .build();
    }

    /** スコア更新（07 §J.2.1 SCORE_UPDATED・機微情報を含まないスコアサマリのみ）。 */
    public static MatchLiveUpdateEvent scoreUpdated(MatchEntity match) {
        return MatchLiveUpdateEvent.builder()
                .matchId(match.getId())
                .type(MatchLiveUpdateType.SCORE_UPDATED)
                .score(MatchLiveScoreSummary.builder()
                        .homeScore(match.getHomeScore())
                        .awayScore(match.getAwayScore())
                        .homePenaltyScore(match.getHomePenaltyScore())
                        .awayPenaltyScore(match.getAwayPenaltyScore())
                        .build())
                .build();
    }

    /** ステータス遷移（07 §J.2.1 STATUS_CHANGED）。 */
    public static MatchLiveUpdateEvent statusChanged(UUID matchId, MatchStatus status) {
        return MatchLiveUpdateEvent.builder()
                .matchId(matchId)
                .type(MatchLiveUpdateType.STATUS_CHANGED)
                .status(status)
                .build();
    }
}
