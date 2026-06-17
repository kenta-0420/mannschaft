package com.mannschaft.app.timeline.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投稿レスポンスDTO（一覧用）。
 */
@Builder(toBuilder = true)
@Getter
public class PostResponse {

    private final Long id;
    private final PostScopeDto scope;
    private final PostAuthorDto author;
    private final PostContentDto content;
    private final PostStatsDto stats;
    /**
     * 添付ファイル一覧。フィード一覧では空リストを返す（null 不可。FE が attachments.length を参照するため）。
     * 詳細は {@link PostDetailResponse} で取得すること。
     */
    private final List<AttachmentResponse> attachments;
    private final PostAuditDto audit;

    public record PostScopeDto(String scopeType, Long scopeId) {}

    public record PostAuthorDto(Long userId, Long socialProfileId, String postedAsType, Long postedAsId) {}

    public record PostContentDto(String content, Long parentId, Long repostOfId, String status,
                                 LocalDateTime scheduledAt, Boolean isPinned) {}

    public record PostStatsDto(Integer repostCount, Integer reactionCount, Integer replyCount,
                               Short attachmentCount, Short editCount) {}

    public record PostAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
