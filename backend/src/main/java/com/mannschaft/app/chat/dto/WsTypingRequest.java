package com.mannschaft.app.chat.dto;

/**
 * タイピングインジケーター WebSocket リクエスト。
 * クライアントが {@code /app/chat.typing} に SEND するメッセージのペイロード。
 *
 * @param channelId 対象チャンネルID
 */
public record WsTypingRequest(
        Long channelId
) {
}
