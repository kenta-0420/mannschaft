package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セッションのデバイス名変更リクエスト（F12.4）。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSessionDeviceNameRequest {

    @NotBlank
    @Size(min = 1, max = 100)
    @Pattern(regexp = "^[^\\p{Cc}]+$", message = "制御文字は使用できません")
    private final String deviceName;
}
