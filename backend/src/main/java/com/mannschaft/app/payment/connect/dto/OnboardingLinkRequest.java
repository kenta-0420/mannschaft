package com.mannschaft.app.payment.connect.dto;

import com.mannschaft.app.payment.connect.ScopeKind;
import jakarta.validation.constraints.NotNull;

/**
 * F22.1 謝礼決済: Connect onboarding リンク発行リクエスト（設計書 02 §2.1）。
 *
 * <p>{@code scopeId} は TEAM/ORG 時必須（teamId/orgId）。USER 時は無視され
 * 本人（{@code SecurityUtils.getCurrentUserId()}）に固定される（03 §3）。</p>
 *
 * @param scopeKind  受領主体種別（USER/TEAM/ORG）
 * @param scopeId    受領主体 ID（TEAM/ORG 時必須・USER 時は無視）
 * @param returnUrl  onboarding 完了後の戻り URL
 * @param refreshUrl リンク失効時の再発行 URL
 */
public record OnboardingLinkRequest(
        @NotNull ScopeKind scopeKind,
        Long scopeId,
        @NotNull String returnUrl,
        @NotNull String refreshUrl) {
}
