package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 参加チーム追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateParticipantRequest {

    @NotNull
    private final Long teamId;

    private final Integer seed;

    @Size(max = 100)
    private final String displayName;
}
