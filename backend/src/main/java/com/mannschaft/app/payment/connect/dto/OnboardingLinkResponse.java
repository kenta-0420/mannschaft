package com.mannschaft.app.payment.connect.dto;

import com.mannschaft.app.payment.connect.OnboardingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Connect onboarding リンク発行レスポンス（設計書 02 §2.1）。
 *
 * <p>PCI 禁則（03 §4・04 §1.3）: {@code client_secret}/{@code pi_} 等の生 Stripe 機密は含めない。
 * {@code stripeAccountId}（{@code acct_xxx}）は受領者本人にのみ返す自分の口座識別子であり、
 * 一覧・公開 API には載せない（本レスポンスは認可済み本人/scope ADMIN 専用）。</p>
 *
 * @param connectAccountId 内部 Connect アカウント ID（UUIDv7）
 * @param stripeAccountId  Stripe Connect アカウント ID（{@code acct_xxx}）
 * @param onboardingStatus onboarding 状態
 * @param onboardingUrl    Stripe hosted onboarding への遷移 URL
 * @param expiresAt        リンク失効時刻
 */
public record OnboardingLinkResponse(
        UUID connectAccountId,
        String stripeAccountId,
        OnboardingStatus onboardingStatus,
        String onboardingUrl,
        LocalDateTime expiresAt) {
}
