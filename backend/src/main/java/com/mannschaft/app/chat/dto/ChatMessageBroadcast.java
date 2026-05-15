package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebSocket STOMP でチャンネル参加者全員に配信するメッセージイベントのラッパー。
 * <p>
 * 配信先: {@code /topic/channels/{channelId}}
 * </p>
 * <p>
 * type: MESSAGE_CREATED / MESSAGE_UPDATED / MESSAGE_DELETED / REACTION_UPDATED / TYPING
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageBroadcast(String type, Object data) {

    public static ChatMessageBroadcast messageCreated(Object data) {
        return new ChatMessageBroadcast("MESSAGE_CREATED", data);
    }

    public static ChatMessageBroadcast messageUpdated(Object data) {
        return new ChatMessageBroadcast("MESSAGE_UPDATED", data);
    }

    public static ChatMessageBroadcast messageDeleted(Object data) {
        return new ChatMessageBroadcast("MESSAGE_DELETED", data);
    }

    public static ChatMessageBroadcast reactionUpdated(Object data) {
        return new ChatMessageBroadcast("REACTION_UPDATED", data);
    }

    public static ChatMessageBroadcast typing(Object data) {
        return new ChatMessageBroadcast("TYPING", data);
    }
}
