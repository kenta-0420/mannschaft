package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotNull;

/**
 * オーナー委譲オファーの作成（打診）リクエスト（F01.2・承諾型）。
 *
 * <p>パラメータ名は {@code targetUserId}（camelCase 統一・設計書 02_api_design）。</p>
 *
 * @param targetUserId 委譲先（指名相手）ユーザー ID
 */
public record TransferOwnershipOfferCreateRequest(
        @NotNull Long targetUserId
) {
}
