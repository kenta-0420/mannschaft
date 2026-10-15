package com.mannschaft.app.social.announcement.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * お知らせフィード一覧のページングメタ情報 DTO（F02.6）。
 *
 * <p>
 * {@code GET /api/v1/teams/{teamId}/announcements} および
 * {@code GET /api/v1/announcements/me} のレスポンスに含まれる
 * カーソルページング用メタデータ。
 * </p>
 */
@Getter
@Builder
public class AnnouncementFeedMetaDto {

    /** 次ページのカーソル値（null = 次ページなし） */
    private final Long nextCursor;

    /** 次ページが存在するかどうか */
    private final boolean hasNext;

    /** スコープ内の未読件数 */
    private final long unreadCount;
}
