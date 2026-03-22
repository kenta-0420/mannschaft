package com.mannschaft.app.line.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * LINE BOT設定作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateLineBotConfigRequest {

    @NotBlank
    @Size(max = 100)
    private final String channelId;

    @NotBlank
    private final String channelSecret;

    @NotBlank
    private final String channelAccessToken;

    @NotBlank
    @Size(max = 64)
    private final String webhookSecret;

    @Size(max = 50)
    private final String botUserId;

    private final Boolean notificationEnabled;
}
