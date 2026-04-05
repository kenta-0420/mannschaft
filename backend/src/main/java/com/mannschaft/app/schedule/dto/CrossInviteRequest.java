package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * クロス招待リクエストDTO。チーム・組織間のスケジュール招待に使用する。
 */
@Getter
@RequiredArgsConstructor
public class CrossInviteRequest {

    @NotNull
    private final String targetType;

    @NotNull
    private final Long targetId;

    @Size(max = 500)
    private final String message;
}
