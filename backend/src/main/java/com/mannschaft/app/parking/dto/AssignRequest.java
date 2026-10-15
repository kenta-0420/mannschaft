package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 区画割り当てリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AssignRequest {

    @NotNull
    private final Long userId;

    private final Long vehicleId;

    private final LocalDate contractStartDate;

    private final LocalDate contractEndDate;
}
