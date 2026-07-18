package com.mannschaft.app.role.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * オーナー委譲オファーの作成（打診）レスポンス（F01.2・承諾型・201 Created）。
 *
 * <p>JSON 契約は camelCase。{@code offerId} は UUIDv7 を文字列で返す。</p>
 *
 * @param offerId   オファー ID（UUIDv7）
 * @param status    オファー状態（作成直後は {@code PENDING}）
 * @param target    指名相手の概要
 * @param issuedBy  発行者（現 ADMIN）の概要
 * @param expiresAt 有効期限
 */
public record TransferOwnershipOfferResponse(
        UUID offerId,
        String status,
        UserBrief target,
        UserBrief issuedBy,
        LocalDateTime expiresAt
) {

    /**
     * オファー当事者ユーザーの概要。
     *
     * @param userId      ユーザー ID
     * @param displayName 表示名
     */
    public record UserBrief(
            Long userId,
            String displayName
    ) {
    }
}
