package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 区画申請リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateApplicationRequest {

    @NotNull
    private final Long spaceId;

    @NotNull
    private final Long vehicleId;

    @Size(max = 500)
    private final String message;
}
