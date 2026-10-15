package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * 連絡先アイテムDTO。
 * フォルダ内の1件の連絡先を表す。
 * displayName は customName が設定されていれば優先、なければ UserEntity.displayName を使用する。
 */
public record ContactItemDto(
        Long itemId,
        String displayName,
        String avatarUrl,
        boolean isPinned,
        boolean hasActiveDm,
        /** 対応する DM チャンネルの最終メッセージ日時。DM 未開始の場合は null */
        LocalDateTime lastMessageAt
) {}
