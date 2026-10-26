package com.mannschaft.app.social.announcement.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * お知らせフィード一覧レスポンス DTO（F02.6）。
 *
 * <p>
 * {@code GET /api/v1/teams/{teamId}/announcements}、
 * {@code GET /api/v1/organizations/{orgId}/announcements}、
 * {@code GET /api/v1/announcements/me} の共通レスポンス形式。
 * </p>
 *
 * <pre>{@code
 * {
 *   "data": [ ... ],
 *   "meta": {
 *     "nextCursor": 95,
 *     "hasNext": true,
 *     "unreadCount": 3
 *   }
 * }
 * }</pre>
 */
@Getter
@Builder
public class AnnouncementFeedResponseDto {

    /** お知らせフィードアイテムリスト */
    private final List<AnnouncementFeedItemDto> data;

    /** ページングメタ情報 */
    private final AnnouncementFeedMetaDto meta;
}
