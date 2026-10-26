package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 音声入力同意リクエスト。
 */
public record VoiceInputConsentRequest(

        @NotNull
        @Min(1)
        Integer version
) {}
