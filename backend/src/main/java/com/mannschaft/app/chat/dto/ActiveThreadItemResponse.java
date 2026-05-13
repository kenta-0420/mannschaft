package com.mannschaft.app.chat.dto;

import java.time.LocalDateTime;

/**
 * アクティブスレッド一覧の1件レスポンス。
 */
public record ActiveThreadItemResponse(
        Long id,
        Long senderId,
        String senderDisplayName,
        String body,
        int replyCount,
        LocalDateTime lastReplyAt,
        String lastReplyPreview,
        LocalDateTime createdAt
) {}
