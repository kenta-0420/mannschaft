package com.mannschaft.app.line.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SNSフィード設定作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateSnsFeedConfigRequest {

    @NotBlank
    @Size(max = 20)
    private final String provider;

    @NotBlank
    @Size(max = 100)
    private final String accountUsername;

    private final String accessToken;

    private final Short displayCount;
}
