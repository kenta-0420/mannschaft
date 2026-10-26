package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チャンネル更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateChannelRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 500)
    private final String iconKey;
}
