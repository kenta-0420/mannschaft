package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * チャットからチーム/組織への承諾型招待の発行リクエスト（F04.12）。
 *
 * <p>JSON 契約は camelCase（設計書 D-12）。</p>
 *
 * @param scopeType     招待先種別（{@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId       招待先チーム/組織の ID
 * @param roleId        参加時に付与するロール。null 時は当該スコープの MEMBER ロール。
 *                      特権ロール（ADMIN/DEPUTY_ADMIN）指定は 422（設計書 §6・C-1）
 * @param expiresInDays 有効期限日数。許容値は 1 / 7 / 30 / 90 のみ。null 時は 7
 */
public record MembershipInviteRequest(
        @NotBlank String scopeType,
        @NotNull Long scopeId,
        Long roleId,
        Integer expiresInDays
) {
}
