package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メッセージレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MessageResponse {

    private final Long id;
    private final Long channelId;
    private final Long senderId;
    private final Long parentId;
    private final String body;
    private final Long forwardedFromId;
    private final Boolean isEdited;
    private final Boolean isSystem;
    private final LocalDateTime scheduledAt;
    private final Integer replyCount;
    private final Integer reactionCount;
    private final Boolean isPinned;
    private final List<AttachmentResponse> attachments;
    private final List<ReactionResponse> reactions;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
