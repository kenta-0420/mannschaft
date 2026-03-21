package com.mannschaft.app.digest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイジェスト手動生成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class DigestGenerateRequest {

    @NotBlank
    private final String scopeType;

    @NotNull
    private final Long scopeId;

    @NotNull
    private final LocalDateTime periodStart;

    @NotNull
    private final LocalDateTime periodEnd;

    private final String digestStyle;

    @Size(max = 500)
    private final String customPromptSuffix;

    private final String presetName;
}
