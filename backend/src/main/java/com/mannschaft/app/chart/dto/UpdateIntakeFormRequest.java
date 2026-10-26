package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 問診票更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateIntakeFormRequest {

    @NotBlank
    @Size(max = 20)
    private final String formType;

    @NotNull
    private final String content;

    private final Long electronicSealId;

    private final Boolean isInitial;
}
