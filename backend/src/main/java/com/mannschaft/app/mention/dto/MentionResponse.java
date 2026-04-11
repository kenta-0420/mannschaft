package com.mannschaft.app.mention.dto;

import java.time.LocalDateTime;

/**
 * メンションレスポンス DTO。
 *
 * <p>フロントエンドの {@code MentionList.vue} が期待するレスポンス構造に合わせる。</p>
 *
 * @param id              メンション ID
 * @param mentionedBy     メンションしたユーザー情報
 * @param contentType     コンテンツ種別 (POST, MESSAGE, THREAD, COMMENT)
 * @param contentId       コンテンツ ID
 * @param contentTitle    コンテンツタイトル（Phase 1 では target_type から生成）
 * @param contentSnippet  本文の抜粋
 * @param url             遷移先 URL
 * @param isRead          既読フラグ
 * @param createdAt       作成日時
 */
public record MentionResponse(
        Long id,
        MentionedByUser mentionedBy,
        String contentType,
        Long contentId,
        String contentTitle,
        String contentSnippet,
        String url,
        boolean isRead,
        LocalDateTime createdAt
) {

    /**
     * メンションしたユーザーのサマリー情報。
     *
     * @param id          ユーザー ID
     * @param displayName 表示名
     * @param avatarUrl   アバター URL（null 可）
     */
    public record MentionedByUser(
            Long id,
            String displayName,
            String avatarUrl
    ) {
    }
}
