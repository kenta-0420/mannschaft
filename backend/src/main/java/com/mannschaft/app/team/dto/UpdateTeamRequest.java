package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTeamRequest {

    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String template;
    private final String prefecture;
    private final String city;
    private final String visibility;
    private final Boolean supporterEnabled;

    @NotNull
    private final Long version;
}
