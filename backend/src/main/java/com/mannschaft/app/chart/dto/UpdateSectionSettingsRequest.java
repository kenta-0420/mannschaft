package com.mannschaft.app.chart.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * セクション設定一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSectionSettingsRequest {

    @NotNull
    @Valid
    private final List<SectionSettingRequest> sections;
}
