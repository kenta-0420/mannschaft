package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.PlayerAppearanceEntity;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * 出場記録のレスポンス DTO（02 §F.4・自動算出 computed_minutes 込み）。
 *
 * <p>{@code owning_team_id}（権限列）はユーザーに不可視のため露出しない（03 §C.2）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.3 / 02 §F.4</p>
 */
@Getter
@Builder
public class PlayerAppearanceResponse {

    private final UUID id;
    private final UUID matchId;
    private final Long playerUserId;
    private final String playerName;
    private final TeamSide teamSide;
    private final boolean starter;
    private final String position;
    private final Integer jerseyNumber;
    private final Integer firstInMinute;
    private final Integer lastOutMinute;
    /** 自動算出出場分（NULL=不明＝duration 未設定で確定不可・02 §E.1）。 */
    private final Integer computedMinutes;

    public static PlayerAppearanceResponse from(PlayerAppearanceEntity appearance) {
        return PlayerAppearanceResponse.builder()
                .id(appearance.getId())
                .matchId(appearance.getMatchId())
                .playerUserId(appearance.getPlayerUserId())
                .playerName(appearance.getPlayerName())
                .teamSide(appearance.getTeamSide())
                .starter(appearance.isStarter())
                .position(appearance.getPosition())
                .jerseyNumber(appearance.getJerseyNumber())
                .firstInMinute(appearance.getFirstInMinute())
                .lastOutMinute(appearance.getLastOutMinute())
                .computedMinutes(appearance.getComputedMinutes())
                .build();
    }
}
