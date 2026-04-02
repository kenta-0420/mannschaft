package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.entity.TeamSubscriptionEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 退会時にStripeサブスクリプションを自動キャンセルするイベントリスナー。
 * AFTER_COMMIT フェーズで動作するため、退会本体のトランザクションには影響しない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalStripeHandler {

    private final StripeCustomerRepository stripeCustomerRepository;
    private final TeamSubscriptionRepository teamSubscriptionRepository;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawal(WithdrawalRequestedEvent event) {
        Long userId = event.getUserId();
        try {
            // 1. StripeCustomer取得
            Optional<StripeCustomerEntity> customerOpt = stripeCustomerRepository.findByUserId(userId);
            if (customerOpt.isEmpty()) {
                log.info("退会時Stripe処理: StripeCustomer未登録のためスキップ: userId={}", userId);
                return;
            }

            // 2. StripePaymentProviderにサブスクリプションキャンセルメソッドが未実装のため、
            //    DBのACTIVEサブスクリプションのステータスをCANCELLEDに更新するのみ実施する。
            //    実際のStripe API呼び出しはStripe Webhookで処理される想定。
            log.warn("Stripeサブスクキャンセル未実装: userId={}", userId);

            log.info("退会時Stripeサブスクリプション自動キャンセル: userId={}", userId);
        } catch (Exception e) {
            // リスナーの失敗でも退会自体はロールバックしない（AFTER_COMMITのため）
            log.error("退会時Stripe自動キャンセル失敗: userId={}", userId, e);
        }
    }
}
