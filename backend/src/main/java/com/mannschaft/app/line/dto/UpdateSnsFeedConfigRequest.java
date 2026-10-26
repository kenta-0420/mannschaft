package com.mannschaft.app.line.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SNSフィード設定更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSnsFeedConfigRequest {

    @NotBlank
    @Size(max = 100)
    private final String accountUsername;

    private final String accessToken;

    private final Short displayCount;

    private final Boolean isActive;
}
