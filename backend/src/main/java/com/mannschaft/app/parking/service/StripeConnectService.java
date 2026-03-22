package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.dto.StripeConnectStatusResponse;
import com.mannschaft.app.parking.entity.StripeConnectAccountEntity;
import com.mannschaft.app.parking.repository.StripeConnectAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stripe Connect サービス（プレースホルダー）。
 * 実際のStripe API連携は将来の実装で追加する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StripeConnectService {

    private final StripeConnectAccountRepository stripeConnectAccountRepository;

    /**
     * オンボーディングを開始する（プレースホルダー）。
     * 実際にはStripe APIを呼び出してAccount Linkを生成する。
     */
    @Transactional
    public String startOnboarding(Long userId) {
        StripeConnectAccountEntity account = stripeConnectAccountRepository.findByUserId(userId)
                .orElseGet(() -> {
                    StripeConnectAccountEntity newAccount = StripeConnectAccountEntity.builder()
                            .userId(userId)
                            .stripeAccountId("acct_placeholder_" + userId)
                            .build();
                    return stripeConnectAccountRepository.save(newAccount);
                });
        log.info("Stripe Connect オンボーディング開始: userId={}, stripeAccountId={}", userId, account.getStripeAccountId());
        // TODO: Stripe API呼び出しでAccount Linkを生成し、URLを返す
        return "https://connect.stripe.com/setup/placeholder";
    }

    /**
     * Stripe Connectのステータスを取得する。
     */
    public StripeConnectStatusResponse getStatus(Long userId) {
        StripeConnectAccountEntity account = stripeConnectAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.STRIPE_CONNECT_NOT_CONFIGURED));
        return new StripeConnectStatusResponse(
                account.getUserId(), account.getStripeAccountId(),
                account.getChargesEnabled(), account.getPayoutsEnabled(), account.getOnboardingCompleted());
    }
}
