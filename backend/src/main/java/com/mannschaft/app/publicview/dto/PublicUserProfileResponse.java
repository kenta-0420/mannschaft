package com.mannschaft.app.publicview.dto;

import java.time.LocalDate;

/**
 * F19.1 Phase 6: 公開ユーザープロフィール用の<strong>抑制版</strong>レスポンス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6</p>
 *
 * <p>このレコードは認証不要エンドポイント
 * {@code GET /api/v1/public/users/{userId}} のレスポンスとして返却される。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>氏名 / メール / 電話 / 住所などの PII</li>
 *   <li>{@code status} / {@code deletedAt} / {@code archivedAt} などの内部状態</li>
 *   <li>認証系情報（passwordHash, refreshToken 等）</li>
 * </ul>
 *
 * @param userId      ユーザー ID
 * @param displayName 表示名（匿名化済みの場合は「退会済みユーザー」等）
 * @param avatarUrl   アバター画像 URL（null 可）
 * @param memberSince 登録日（createdAt.toLocalDate()）
 */
public record PublicUserProfileResponse(
        Long userId,
        String displayName,
        String avatarUrl,
        LocalDate memberSince
) {}
