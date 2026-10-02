package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F08.9 件2 保護者による子データ閲覧（お知らせ受信）レスポンス。
 *
 * <p>{@code GET /api/v1/me/guardianship/children/{childUserId}/announcements} の返却。
 * 子が所属する全スコープ（チーム/組織）の掲示板スレッドを合算し、更新日時降順でページングして返す。
 * bulletin ドメインの {@code ThreadResponse} を素で外へ出さず、閲覧見守りに必要な最小項目へ縮約する。</p>
 *
 * @param items         お知らせ項目（更新日時降順）
 * @param page          ページ番号（0 始まり）
 * @param size          ページサイズ
 * @param totalElements 全所属スコープの合算件数
 */
public record GuardianChildAnnouncementsResponse(
        List<AnnouncementItem> items,
        int page,
        int size,
        long totalElements) {

    /**
     * お知らせ 1 件（掲示板スレッドの縮約）。
     *
     * @param threadId  スレッド ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param scopeName スコープ表示名
     * @param title     タイトル
     * @param priority  優先度（INFO / IMPORTANT 等）
     * @param createdAt 作成日時
     * @param updatedAt 更新日時（ソートキー）
     */
    public record AnnouncementItem(
            Long threadId,
            String scopeType,
            Long scopeId,
            String scopeName,
            String title,
            String priority,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
