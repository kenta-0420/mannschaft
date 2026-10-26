package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * DM用カードDTO。
 * DIRECT（1対1 DM / GROUP_DM）チャンネルのチャットハブ表示に使用する。
 */
public record DirectMessageItemDto(
        Long channelId,
        Long partnerId,
        String partnerDisplayName,
        String partnerAvatarUrl,
        int unreadCount,
        boolean isPinned,
        boolean isMuted,
        LocalDateTime lastMessageAt,
        /** 最終メッセージのプレビュー（最大50文字）。lastMessageAt が null の場合は null */
        String lastMessagePreview
) {}
