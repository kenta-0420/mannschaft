package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通報エスカレーションリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EscalateRequest {

    @NotBlank
    @Size(max = 2000)
    private final String reason;

    @Size(max = 100)
    private final String guidelineSection;
}
