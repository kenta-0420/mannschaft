package com.mannschaft.app.scopefolder.dto;

/**
 * フォルダ別未読通知件数の集計レスポンスDTO（タブバッジ用）。
 *
 * <p>設計書 F15.3 §5.2.3 / §6.4</p>
 *
 * @param folderId    フォルダ ID
 * @param unreadCount 未読件数
 */
public record FolderNotificationSummaryDto(
        Long folderId,
        long unreadCount
) {
}
