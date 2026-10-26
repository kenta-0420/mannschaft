package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト希望更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateShiftRequestRequest {

    @NotNull
    private final String preference;

    @Size(max = 200)
    private final String note;
}
