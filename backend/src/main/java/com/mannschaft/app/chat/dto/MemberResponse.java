package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チャンネルメンバーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberResponse {

    private final Long id;
    private final Long channelId;
    private final Long userId;
    private final String role;
    private final Integer unreadCount;
    private final LocalDateTime lastReadAt;
    private final Boolean isMuted;
    private final Boolean isPinned;
    private final String category;
    private final LocalDateTime joinedAt;
}
