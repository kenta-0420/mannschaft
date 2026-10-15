package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セクション設定リクエストDTO（1件分）。
 */
@Getter
@RequiredArgsConstructor
public class SectionSettingRequest {

    @NotBlank
    @Size(max = 30)
    private final String sectionType;

    @NotNull
    private final Boolean isEnabled;
}
