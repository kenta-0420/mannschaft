package com.mannschaft.app.role.dto;

import java.time.LocalDateTime;

/**
 * 招待トークン一覧の 1 行。平文トークンは持たない（DB にはハッシュしか無く、再表示できない）。
 *
 * @param id        招待トークン ID
 * @param roleName  付与ロール名
 * @param expiresAt 有効期限
 * @param maxUses   使用回数上限
 * @param usedCount 使用回数
 * @param revokedAt 失効日時
 * @param createdAt 発行日時
 */
public record InviteTokenResponse(
        Long id,
        String roleName,
        LocalDateTime expiresAt,
        Integer maxUses,
        Integer usedCount,
        LocalDateTime revokedAt,
        LocalDateTime createdAt) {
}
