package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * DM用カードDTO。
 * DIRECT（1対1 DM / GROUP_DM）チャンネルのチャットハブ表示に使用する。
 */
public record DirectMessageItemDto(
        Long channelId,
        Long partnerId,
        String partnerDisplayName,
        String partnerAvatarUrl,
        int unreadCount,
        boolean isPinned,
        boolean isMuted,
        LocalDateTime lastMessageAt,
        /** 最終メッセージのプレビュー（最大50文字）。lastMessageAt が null の場合は null */
        String lastMessagePreview,
        /**
         * GROUP_DM の表示名（CMP-261113-0110）。明示名 → 参加者名からの導出名 → null（FE が未命名文言を出す）の順。
         * 1対1 DM は null。（試練段階ではフィールドのみ定義。値の解決は出陣で実装する）
         */
        String derivedName
) {

    /** 導出名を持たない従来の構築（試練段階の互換用。出陣で導出名を渡す形へ置き換える）。 */
    public DirectMessageItemDto(Long channelId, Long partnerId, String partnerDisplayName, String partnerAvatarUrl,
                                int unreadCount, boolean isPinned, boolean isMuted, LocalDateTime lastMessageAt,
                                String lastMessagePreview) {
        this(channelId, partnerId, partnerDisplayName, partnerAvatarUrl, unreadCount, isPinned, isMuted,
                lastMessageAt, lastMessagePreview, null);
    }
}
