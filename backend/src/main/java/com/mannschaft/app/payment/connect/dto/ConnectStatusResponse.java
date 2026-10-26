package com.mannschaft.app.payment.connect.dto;

import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;

import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Connect 状態照会レスポンス（設計書 02 §2.2）。
 *
 * <p>PCI 禁則（03 §4）: 決済トークン（{@code client_secret}/{@code pi_}）は含めない。
 * IDOR（03 §4・§5）: scope 所有権を照合した本人/scope ADMIN にのみ返す。</p>
 *
 * @param connectAccountId 内部 Connect アカウント ID（UUIDv7）
 * @param scopeKind        受領主体種別
 * @param scopeId          受領主体 ID
 * @param onboardingStatus onboarding 状態
 * @param chargesEnabled   課金可否
 * @param payoutsEnabled   払出可否
 * @param requirementsDue  KYC 要件不足項目（RESTRICTED 時のみ非空）
 */
public record ConnectStatusResponse(
        UUID connectAccountId,
        ScopeKind scopeKind,
        Long scopeId,
        OnboardingStatus onboardingStatus,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        List<String> requirementsDue) {
}
