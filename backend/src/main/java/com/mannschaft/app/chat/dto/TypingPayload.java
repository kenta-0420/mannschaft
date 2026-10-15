package com.mannschaft.app.chat.dto;

/**
 * タイピング中ユーザー情報のペイロード。
 * WebSocket トピックに配信される {@link ChatMessageBroadcast} の data フィールドに使用する。
 *
 * @param userId      タイピング中のユーザーID
 * @param displayName タイピング中のユーザー表示名
 */
public record TypingPayload(
        Long userId,
        String displayName
) {
}
