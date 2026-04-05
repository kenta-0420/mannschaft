package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チャンネル個人設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChannelSettingsRequest {

    private final Boolean isMuted;

    private final Boolean isPinned;

    @Size(max = 50)
    private final String category;
}
