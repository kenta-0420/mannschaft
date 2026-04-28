package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * F04.2.1 §3.10.1: /topic/channels/{id}/events へ送信するイベントペイロード。
 * <p>
 * type: MEMBER_KICKED / CHANNEL_DELETED / CHANNEL_ARCHIVED / CHANNEL_UNARCHIVED
 * MEMBER_KICKED の場合のみ userId フィールドを持つ。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatChannelEventPayload(String type, Long userId) {

    public static ChatChannelEventPayload memberKicked(Long userId) {
        return new ChatChannelEventPayload("MEMBER_KICKED", userId);
    }

    public static ChatChannelEventPayload channelDeleted() {
        return new ChatChannelEventPayload("CHANNEL_DELETED", null);
    }

    public static ChatChannelEventPayload channelArchived() {
        return new ChatChannelEventPayload("CHANNEL_ARCHIVED", null);
    }

    public static ChatChannelEventPayload channelUnarchived() {
        return new ChatChannelEventPayload("CHANNEL_UNARCHIVED", null);
    }
}
