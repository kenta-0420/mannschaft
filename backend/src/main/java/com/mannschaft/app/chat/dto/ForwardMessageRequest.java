package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メッセージ転送リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ForwardMessageRequest {

    @NotNull
    private final Long targetChannelId;

    private final String additionalComment;
}
