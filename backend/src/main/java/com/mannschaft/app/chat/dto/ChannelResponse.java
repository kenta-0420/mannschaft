package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チャンネルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChannelResponse {

    private final Long id;
    private final String channelType;
    private final Long teamId;
    private final Long organizationId;
    private final String name;
    private final String iconKey;
    private final String description;
    private final Boolean isPrivate;
    private final Long createdBy;
    private final LocalDateTime lastMessageAt;
    private final String lastMessagePreview;
    private final String sourceType;
    private final Long sourceId;
    private final Boolean isArchived;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
