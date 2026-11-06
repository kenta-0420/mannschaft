package com.mannschaft.app.role.dto;

import java.time.LocalDateTime;

/**
 * 共有リンク型招待トークンの発行応答。平文 {@code token} を載せるのは発行時の一度きり。
 *
 * <p>DB にはハッシュしか残らないため、後からは再取得できない。一覧は平文を持たない
 * {@link InviteTokenSummary} を返す。</p>
 *
 * @param id        招待トークン ID
 * @param token     平文トークン（Base64URL 43字）。発行時にだけ返す
 * @param roleName  付与ロール名
 * @param expiresAt 有効期限
 * @param maxUses   使用回数上限
 * @param usedCount 使用回数
 * @param revokedAt 失効日時
 * @param createdAt 発行日時
 */
public record InviteTokenIssuedResponse(
        Long id,
        String token,
        String roleName,
        LocalDateTime expiresAt,
        Integer maxUses,
        Integer usedCount,
        LocalDateTime revokedAt,
        LocalDateTime createdAt) {
}
