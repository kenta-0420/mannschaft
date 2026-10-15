package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 参加チーム更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateParticipantRequest {

    private final Integer seed;

    @Size(max = 100)
    private final String displayName;

    private final String status;
}
