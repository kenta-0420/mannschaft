package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ記事レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlogPostResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final Long userId;
    private final Long authorId;
    private final String title;
    private final String slug;
    private final String body;
    private final String excerpt;
    private final String coverImageUrl;
    private final String postType;
    private final String visibility;
    private final String priority;
    private final String status;
    private final LocalDateTime publishedAt;
    private final Boolean pinned;
    private final Boolean allowComments;
    private final Integer viewCount;
    private final Short readingTimeMinutes;
    private final Integer version;
    private final Long seriesId;
    private final Short seriesOrder;
    private final List<TagSummary> tags;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final boolean mitayo;
    private final int mitayoCount;

    /**
     * リアクション情報（みたよ！状態・件数）を付与した新しいインスタンスを返す。
     */
    public BlogPostResponse withReaction(boolean mitayo, int mitayoCount) {
        return new BlogPostResponse(
                this.id, this.teamId, this.organizationId, this.userId, this.authorId,
                this.title, this.slug, this.body, this.excerpt, this.coverImageUrl,
                this.postType, this.visibility, this.priority, this.status,
                this.publishedAt, this.pinned, this.allowComments, this.viewCount,
                this.readingTimeMinutes, this.version, this.seriesId, this.seriesOrder,
                this.tags, this.createdAt, this.updatedAt, mitayo, mitayoCount);
    }

    /**
     * タグの要約情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TagSummary {
        private final Long id;
        private final String name;
        private final String color;
    }
}
