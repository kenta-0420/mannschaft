package com.mannschaft.app.supporter.dto;

/**
 * サポーター申請情報レスポンス。
 *
 * @param id          申請ID（approve/reject に使用）
 * @param userId      申請者ユーザーID
 * @param displayName 申請者の表示名
 * @param avatarUrl   申請者のアバター画像URL（null可）
 * @param message     申請メッセージ（null可）
 * @param status      PENDING / APPROVED / REJECTED
 * @param createdAt   申請日時（ISO8601）
 */
public record SupporterApplicationResponse(
        Long id,
        Long userId,
        String displayName,
        String avatarUrl,
        String message,
        String status,
        String createdAt
) {
}
