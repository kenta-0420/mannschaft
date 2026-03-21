package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投稿詳細レスポンスDTO。添付ファイル・リアクション集計・投票を含む。
 */
@Getter
@RequiredArgsConstructor
public class PostDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long userId;
    private final Long socialProfileId;
    private final String postedAsType;
    private final Long postedAsId;
    private final Long parentId;
    private final String content;
    private final Long repostOfId;
    private final Integer repostCount;
    private final String status;
    private final LocalDateTime scheduledAt;
    private final Boolean isPinned;
    private final Integer reactionCount;
    private final Integer replyCount;
    private final Short attachmentCount;
    private final Short editCount;
    private final List<AttachmentResponse> attachments;
    private final List<ReactionSummaryResponse> reactions;
    private final PollResponse poll;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
