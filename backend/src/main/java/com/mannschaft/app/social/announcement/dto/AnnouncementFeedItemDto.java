package com.mannschaft.app.social.announcement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * お知らせフィード 1 件分のレスポンス DTO（F02.6）。
 *
 * <p>
 * {@link AnnouncementFeedService.AnnouncementFeedItem}（Entity + isRead）から変換して返す。
 * </p>
 *
 * <p>
 * Jackson の boolean フィールドは {@code getIsPinned()} となり JSON キーが {@code "isPinned"} に変換されるが、
 * クライアントとの契約は {@code "isPinned"} / {@code "isRead"} であるため
 * {@link JsonProperty} で明示的に指定する。
 * </p>
 */
@Getter
@Builder
public class AnnouncementFeedItemDto {

    /** お知らせフィード ID */
    private final Long id;

    /** 表示スコープ種別（TEAM / ORGANIZATION） */
    private final String scopeType;

    /** 表示スコープ ID */
    private final Long scopeId;

    /** 元コンテンツ種別（BLOG_POST / BULLETIN_THREAD / TIMELINE_POST / CIRCULATION_DOCUMENT / SURVEY） */
    private final String sourceType;

    /** 元コンテンツ ID */
    private final Long sourceId;

    /** お知らせ表示フラグを付けた操作者 ID */
    private final Long authorId;

    /** 表示用タイトル（元コンテンツから非正規化） */
    private final String titleCache;

    /** 本文抜粋（非正規化） */
    private final String excerptCache;

    /** お知らせ優先度（URGENT / IMPORTANT / NORMAL） */
    private final String priority;

    /** ピン留めフラグ */
    @JsonProperty("isPinned")
    private final boolean isPinned;

    /** 閲覧可能範囲（MEMBERS_ONLY / SUPPORTERS_AND_ABOVE / PUBLIC） */
    private final String visibility;

    /** 表示開始日時（ISO 8601）。null = 即時 */
    private final String startsAt;

    /** 表示終了日時（ISO 8601）。null = 期限なし */
    private final String expiresAt;

    /** 既読フラグ */
    @JsonProperty("isRead")
    private final boolean isRead;

    /** レコード作成日時（ISO 8601） */
    private final String createdAt;

    /** レコード更新日時（ISO 8601） */
    private final String updatedAt;

    /** ISO 8601 フォーマッタ */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * {@link AnnouncementFeedService.AnnouncementFeedItem} から DTO へ変換する。
     *
     * @param item Service が返すフィードアイテム（Entity + isRead）
     * @return 変換済み DTO
     */
    public static AnnouncementFeedItemDto from(AnnouncementFeedService.AnnouncementFeedItem item) {
        var feed = item.feed();
        return AnnouncementFeedItemDto.builder()
                .id(feed.getId())
                .scopeType(feed.getScopeType() != null ? feed.getScopeType().name() : null)
                .scopeId(feed.getScopeId())
                .sourceType(feed.getSourceType() != null ? feed.getSourceType().name() : null)
                .sourceId(feed.getSourceId())
                .authorId(feed.getAuthorId())
                .titleCache(feed.getTitleCache())
                .excerptCache(feed.getExcerptCache())
                .priority(feed.getPriority())
                .isPinned(Boolean.TRUE.equals(feed.getIsPinned()))
                .visibility(feed.getVisibility())
                .startsAt(formatDateTime(feed.getStartsAt()))
                .expiresAt(formatDateTime(feed.getExpiresAt()))
                .isRead(item.isRead())
                .createdAt(formatDateTime(feed.getCreatedAt()))
                .updatedAt(formatDateTime(feed.getUpdatedAt()))
                .build();
    }

    /**
     * LocalDateTime を ISO 8601 文字列に変換する。null の場合は null を返す。
     */
    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(FORMATTER) : null;
    }
}
