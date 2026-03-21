package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * NGチーム追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateNgTeamRequest {

    @NotNull
    private final Long blockedTeamId;

    @Size(max = 500)
    private final String reason;
}
