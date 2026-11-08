package com.mannschaft.app.role.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 共有リンク型招待トークンの発行応答。平文 {@code token} を載せるのは発行時の一度きり。
 *
 * <p>DB にはハッシュしか残らないため、後からは再取得できない。一覧は平文を持たない
 * {@link InviteTokenResponse} を返す。本クラスは平文 {@code token} と一覧と同じ {@link InviteTokenResponse} を
 * 合成し、{@link JsonUnwrapped} で平らに出す。JSON の形は {@code id, token, roleName, expiresAt, maxUses,
 * usedCount, revokedAt, createdAt} のまま変わらない。日時のフィールドは {@link InviteTokenResponse} だけが持つ
 * （LocalDateTime の重複を作らない）。</p>
 *
 * @param token  平文トークン（Base64URL 43字）。発行時にだけ返す
 * @param invite 招待の概要（id・付与ロール名・期限・上限・使用回数・失効・発行日時）
 */
public record InviteTokenIssuedResponse(
        String token,
        @JsonUnwrapped InviteTokenResponse invite) {
}
