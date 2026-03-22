package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 譲渡希望への申込リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ListingApplyRequest {

    @NotNull
    private final Long vehicleId;

    @Size(max = 500)
    private final String message;
}
