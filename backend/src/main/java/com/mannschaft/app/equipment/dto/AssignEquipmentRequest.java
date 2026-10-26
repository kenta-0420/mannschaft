package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 備品貸出リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AssignEquipmentRequest {

    @NotNull
    private final Long assignedToUserId;

    @NotNull
    @Min(1)
    private final Integer quantity;

    private final LocalDate expectedReturnAt;

    @Size(max = 300)
    private final String note;
}
