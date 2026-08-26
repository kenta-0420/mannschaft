package com.mannschaft.app.match.live;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEventEntity;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * F08.10 / 07 §J.3.3 ライブ配信用の最小タイムラインイベントビュー（公開可能な進行情報のみ）。
 *
 * <p><b>機微情報の意図的除外（07 §J.3.3 二重防御）</b>: 本ビューは観戦者へブロードキャストされる差分ペイロードに
 * 載るため、漏洩面を最小化する。以下を<b>意図的に含めない</b>:</p>
 * <ul>
 *   <li>{@code recorded_by_team_id}（所有/権限列・03 §C.2「DB 所有はユーザー不可視」）</li>
 *   <li>{@code player_user_id} / {@code related_player_user_id}（選手の内部ユーザー ID）。
 *       公開してよいのは<b>選手表示名</b>のみ（07 §J.3.3）。</li>
 *   <li>編集権限・テナント情報（そもそも本ドメインの DTO に乗らない）</li>
 * </ul>
 *
 * <p>含めるのは「公開可能な試合進行情報」: イベント ID（FE が差分をスナップショットへマージするキー・
 * J.4 の HTTP スナップショットで観戦者が既に取得可能な値と同一）・種別・時刻・ピリオド・サイド・
 * 選手表示名・カード理由・カスタムラベル・備考・連鎖イベント ID・表示順。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2.1 / §J.3.3</p>
 */
@Getter
@Builder
public class MatchLiveEventView {

    /** イベント ID（UUIDv7・FE 差分マージのキー）。 */
    private final UUID id;

    /** イベント発生分（NULL 可）。 */
    private final Integer minute;

    /** アディショナルタイム（NULL 可）。 */
    private final Integer stoppageMinute;

    /** ピリオド（NULL 可）。 */
    private final PeriodType period;

    /** イベント種別。 */
    private final MatchEventType eventType;

    /** カード理由コード（NULL 可）。 */
    private final String cardReasonCode;

    /** カスタムラベル（NULL 可）。 */
    private final String customLabel;

    /** チームサイド（HOME/AWAY）。 */
    private final TeamSide teamSide;

    /** 選手表示名（公開可・内部ユーザー ID は載せない）。 */
    private final String playerName;

    /** 背番号（NULL 可）。 */
    private final Integer jerseyNumber;

    /** 関連選手表示名（アシスト等・公開可・内部ユーザー ID は載せない）。 */
    private final String relatedPlayerName;

    /** 備考（サニタイズ済み・NULL 可）。 */
    private final String note;

    /** 連鎖イベント ID（NULL 可）。 */
    private final UUID linkedEventId;

    /** 表示順序。 */
    private final int sortSeq;

    /**
     * エンティティから最小ビューを構築する。
     *
     * <p><b>内部ユーザー ID（player_user_id / related_player_user_id）・recorded_by_team_id は
     * 写し取らない</b>（07 §J.3.3 機微情報除外）。</p>
     */
    public static MatchLiveEventView from(MatchEventEntity event) {
        return MatchLiveEventView.builder()
                .id(event.getId())
                .minute(event.getMinute())
                .stoppageMinute(event.getStoppageMinute())
                .period(event.getPeriod())
                .eventType(event.getEventType())
                .cardReasonCode(event.getCardReasonCode())
                .customLabel(event.getCustomLabel())
                .teamSide(event.getTeamSide())
                .playerName(event.getPlayerName())
                .jerseyNumber(event.getJerseyNumber())
                .relatedPlayerName(event.getRelatedPlayerName())
                .note(event.getNote())
                .linkedEventId(event.getLinkedEventId())
                .sortSeq(event.getSortSeq())
                .build();
    }
}
