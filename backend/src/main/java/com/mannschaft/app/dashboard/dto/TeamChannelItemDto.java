package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * チームチャンネル用カードDTO。
 * DM を除くグループチャンネル（TEAM_PUBLIC / TEAM_PRIVATE / ORG_PUBLIC / ORG_PRIVATE）の
 * チャットハブ表示に使用する。
 */
public record TeamChannelItemDto(
        Long channelId,
        String channelName,
        String channelType,
        Long teamId,
        Long organizationId,
        int unreadCount,
        boolean isPinned,
        boolean isMuted,
        LocalDateTime lastMessageAt
) {}
