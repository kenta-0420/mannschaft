package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 消耗品消費リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumeEquipmentRequest {

    @NotNull
    @Min(1)
    private final Integer quantity;

    @NotNull
    private final Long consumedByUserId;

    @Size(max = 300)
    private final String note;
}
