package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 備品返却リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReturnEquipmentRequest {

    @NotNull
    private final Long assignmentId;

    @Size(max = 300)
    private final String note;
}
