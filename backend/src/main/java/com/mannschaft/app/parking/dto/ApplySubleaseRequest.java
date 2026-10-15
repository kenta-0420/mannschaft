package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * サブリース申込リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApplySubleaseRequest {

    @NotNull
    private final Long vehicleId;

    @Size(max = 500)
    private final String message;
}
