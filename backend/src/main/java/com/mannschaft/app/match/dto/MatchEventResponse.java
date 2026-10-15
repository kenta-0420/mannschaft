package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEventEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * タイムラインイベントのレスポンス DTO（02 §F.4・04 G）。
 *
 * <p>{@code recorded_by_team_id}（所有/権限列）はユーザーに不可視のため露出しない（03 §C.2）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.2 / 02 §F.4</p>
 */
@Getter
@Builder
public class MatchEventResponse {

    private final UUID id;
    private final UUID matchId;
    private final Integer minute;
    private final Integer stoppageMinute;
    private final PeriodType period;
    private final MatchEventType eventType;
    private final String cardReasonCode;
    private final String customLabel;
    private final TeamSide teamSide;
    private final Long playerUserId;
    private final String playerName;
    private final Integer jerseyNumber;
    private final Long relatedPlayerUserId;
    private final String relatedPlayerName;
    private final String note;
    private final UUID linkedEventId;
    private final String detail;
    private final int sortSeq;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static MatchEventResponse from(MatchEventEntity event) {
        return MatchEventResponse.builder()
                .id(event.getId())
                .matchId(event.getMatchId())
                .minute(event.getMinute())
                .stoppageMinute(event.getStoppageMinute())
                .period(event.getPeriod())
                .eventType(event.getEventType())
                .cardReasonCode(event.getCardReasonCode())
                .customLabel(event.getCustomLabel())
                .teamSide(event.getTeamSide())
                .playerUserId(event.getPlayerUserId())
                .playerName(event.getPlayerName())
                .jerseyNumber(event.getJerseyNumber())
                .relatedPlayerUserId(event.getRelatedPlayerUserId())
                .relatedPlayerName(event.getRelatedPlayerName())
                .note(event.getNote())
                .linkedEventId(event.getLinkedEventId())
                .detail(event.getDetail())
                .sortSeq(event.getSortSeq())
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }
}
