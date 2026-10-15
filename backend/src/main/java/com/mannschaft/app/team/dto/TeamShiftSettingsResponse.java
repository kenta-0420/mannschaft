package com.mannschaft.app.team.dto;

import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import lombok.Builder;
import lombok.Getter;

/**
 * チームシフト設定レスポンスDTO。
 */
@Getter
@Builder
public class TeamShiftSettingsResponse {

    private Long teamId;
    private boolean reminder48hEnabled;
    private boolean reminder24hEnabled;
    private boolean reminder12hEnabled;

    /**
     * エンティティからレスポンスDTOに変換する。
     */
    public static TeamShiftSettingsResponse from(TeamShiftSettingsEntity entity) {
        return TeamShiftSettingsResponse.builder()
                .teamId(entity.getTeamId())
                .reminder48hEnabled(entity.isReminder48hEnabled())
                .reminder24hEnabled(entity.isReminder24hEnabled())
                .reminder12hEnabled(entity.isReminder12hEnabled())
                .build();
    }
}
