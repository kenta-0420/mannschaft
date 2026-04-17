package com.mannschaft.app.social.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * お知らせ化リクエスト DTO（F02.6）。
 *
 * <p>
 * {@code POST /api/v1/teams/{teamId}/announcements} のリクエストボディ。
 * {@code sourceType} と {@code sourceId} でお知らせ化するコンテンツを指定する。
 * </p>
 *
 * <p>
 * バックエンドで以下の検証を追加で実施する（バリデーションアノテーションでカバーできない部分）:
 * <ul>
 *   <li>{@code sourceType} の許可リスト（{@link com.mannschaft.app.social.announcement.AnnouncementSourceType} 参照）</li>
 *   <li>{@code sourceId} の実在チェック・スコープ一致チェック（IDOR 対策）</li>
 *   <li>重複登録チェック（409 Conflict）</li>
 * </ul>
 * </p>
 */
@Getter
@NoArgsConstructor
public class CreateAnnouncementRequestDto {

    /**
     * 元コンテンツ種別。
     * 許可値: BLOG_POST / BULLETIN_THREAD / TIMELINE_POST / CIRCULATION_DOCUMENT / SURVEY
     */
    @NotBlank
    private String sourceType;

    /**
     * 元コンテンツ ID（ポリモルフィック参照）。
     */
    @NotNull
    private Long sourceId;
}
