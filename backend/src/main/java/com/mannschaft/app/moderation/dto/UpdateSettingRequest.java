package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モデレーション設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSettingRequest {

    @NotBlank
    @Size(max = 500)
    private final String settingValue;
}
