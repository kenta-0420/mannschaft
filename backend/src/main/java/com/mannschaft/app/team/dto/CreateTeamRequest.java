package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateTeamRequest {

    @NotBlank
    private final String name;

    private final String template;
    private final String prefecture;
    private final String city;
    private final String visibility;
}
