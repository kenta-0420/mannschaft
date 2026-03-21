package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ミュートリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class MuteRequest {

    @NotBlank
    private final String mutedType;

    @NotNull
    private final Long mutedId;
}
