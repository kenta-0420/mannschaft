package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モジュールレベル別利用可否更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateLevelAvailabilityRequest {

    @NotBlank
    private final String level;

    private final boolean isAvailable;
}
