package com.mannschaft.app.timeline.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投稿詳細レスポンスDTO。添付ファイル・みたよ！状態・投票を含む。
 */
@Builder(toBuilder = true)
@Getter
public class PostDetailResponse {

    private final Long id;
    private final PostScopeDto scope;
    private final PostAuthorDto author;
    private final PostContentDto content;
    private final PostStatsDto stats;
    private final List<AttachmentResponse> attachments;
    private final PollResponse poll;
    private final PostAuditDto audit;

    public record PostScopeDto(String scopeType, Long scopeId) {}

    public record PostAuthorDto(Long userId, Long socialProfileId, String postedAsType, Long postedAsId) {}

    public record PostContentDto(String content, Long parentId, Long repostOfId, String status,
                                 LocalDateTime scheduledAt, Boolean isPinned) {}

    public record PostStatsDto(Integer repostCount, Integer reactionCount, Integer replyCount,
                               Short attachmentCount, Short editCount, int mitayoCount, boolean mitayo) {}

    public record PostAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
