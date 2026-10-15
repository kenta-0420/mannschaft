package com.mannschaft.app.chat.dto;

import java.util.List;

/**
 * チャットスレッド取得 API レスポンス。
 * ルートメッセージと全返信をフラット配列で返す。
 */
public record ThreadResponse(
        MessageResponse root,
        List<MessageResponse> messages,
        int totalCount,
        String nextCursor,
        boolean hasMore
) {}
