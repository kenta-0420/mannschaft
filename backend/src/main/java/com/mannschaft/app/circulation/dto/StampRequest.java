package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 押印リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class StampRequest {

    @NotNull
    private final Long sealId;

    private final String sealVariant;

    private final Short tiltAngle;

    private final Boolean isFlipped;
}
