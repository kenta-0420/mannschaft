package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.dto.StripeConnectStatusResponse;
import com.mannschaft.app.parking.entity.StripeConnectAccountEntity;
import com.mannschaft.app.parking.repository.StripeConnectAccountRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stripe Connect サービス。
 * Stripe API を呼び出して Express アカウントの作成・オンボーディングを管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StripeConnectService {

    private final StripeConnectAccountRepository stripeConnectAccountRepository;

    @Value("${mannschaft.stripe.connect.return-url:https://app.mannschaft.com/settings/stripe/return}")
    private String returnUrl;

    @Value("${mannschaft.stripe.connect.refresh-url:https://app.mannschaft.com/settings/stripe/refresh}")
    private String refreshUrl;

    /**
     * オンボーディングを開始する。
     * Stripe Express アカウントを作成し、Account Link URL を返す。
     */
    @Transactional
    public String startOnboarding(Long userId) {
        StripeConnectAccountEntity account = stripeConnectAccountRepository.findByUserId(userId)
                .orElseGet(() -> {
                    try {
                        Account stripeAccount = Account.create(
                                AccountCreateParams.builder()
                                        .setType(AccountCreateParams.Type.EXPRESS)
                                        .build());

                        StripeConnectAccountEntity newAccount = StripeConnectAccountEntity.builder()
                                .userId(userId)
                                .stripeAccountId(stripeAccount.getId())
                                .build();
                        return stripeConnectAccountRepository.save(newAccount);
                    } catch (StripeException e) {
                        log.error("Stripe Express アカウント作成失敗: userId={}", userId, e);
                        throw new BusinessException(ParkingErrorCode.STRIPE_CONNECT_SETUP_FAILED);
                    }
                });

        log.info("Stripe Connect オンボーディング開始: userId={}, stripeAccountId={}", userId, account.getStripeAccountId());

        try {
            AccountLink accountLink = AccountLink.create(
                    AccountLinkCreateParams.builder()
                            .setAccount(account.getStripeAccountId())
                            .setRefreshUrl(refreshUrl)
                            .setReturnUrl(returnUrl)
                            .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                            .build());
            return accountLink.getUrl();
        } catch (StripeException e) {
            log.error("Stripe Account Link 生成失敗: userId={}", userId, e);
            throw new BusinessException(ParkingErrorCode.STRIPE_CONNECT_SETUP_FAILED);
        }
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
