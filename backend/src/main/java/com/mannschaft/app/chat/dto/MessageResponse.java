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
    /** スレッドルートメッセージID。null の場合は自身がルート。 */
    private final Long rootId;
    /** ネスト深度（0=トップレベル）。 */
    private final int depth;
    /** depth >= 10 の場合 true。掲示板への移行を促す。 */
    private final boolean suggestBoardMigration;
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
