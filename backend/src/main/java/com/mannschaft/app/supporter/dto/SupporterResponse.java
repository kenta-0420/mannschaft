package com.mannschaft.app.supporter.dto;

/**
 * 承認済みサポーター情報レスポンス。
 *
 * @param userId      サポーターのユーザーID
 * @param displayName 表示名
 * @param avatarUrl   アバター画像URL（null可）
 * @param followedAt  サポーター登録日時（ISO8601）
 */
public record SupporterResponse(
        Long userId,
        String displayName,
        String avatarUrl,
        String followedAt
) {
}
