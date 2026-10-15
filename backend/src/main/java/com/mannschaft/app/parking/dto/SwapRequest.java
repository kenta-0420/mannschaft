package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 区画交換リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SwapRequest {

    @NotNull
    private final Long spaceIdA;

    @NotNull
    private final Long spaceIdB;
}
