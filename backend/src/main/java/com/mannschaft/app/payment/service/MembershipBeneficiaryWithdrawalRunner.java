package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.service.MembershipBeneficiaryWithdrawalTxService.Target;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** DB 先行終端後の Stripe 即時取消しを、トランザクション外で実行する。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipBeneficiaryWithdrawalRunner {
    private final MembershipBeneficiaryWithdrawalTxService txService;
    private final StripePaymentProvider stripePaymentProvider;

    public boolean cancelInitial(UUID subscriptionId, Long beneficiaryUserId) {
        try {
            return txService.reserveAndCancel(subscriptionId, beneficiaryUserId).map(this::cancel).orElse(false);
        } catch (Exception e) {
            // 作業行を作る前の DB 失敗は退会状態の backlog が回収する。他契約は継続する。
            log.error("受益者退会取消しの準備に失敗: subscriptionId={}", subscriptionId, e);
            return false;
        }
    }

    public boolean retry(UUID subscriptionId) {
        try {
            return txService.reserveRetry(subscriptionId).map(this::cancel).orElse(false);
        } catch (Exception e) {
            log.error("受益者退会取消しの再試行に失敗: subscriptionId={}", subscriptionId, e);
            return false;
        }
    }

    private boolean cancel(Target target) {
        try {
            if (target.stripeSubscriptionId() != null) {
                stripePaymentProvider.cancelBillingSubscriptionImmediately(target.stripeSubscriptionId(),
                        "withdrawal-beneficiary-cancel-" + target.subscriptionId() + "-"
                                + target.withdrawalAttemptId());
            }
            txService.markSucceeded(target.subscriptionId(), target.withdrawalAttemptId());
            return true;
        } catch (Exception e) {
            try {
                txService.markFailed(target.subscriptionId(), target.withdrawalAttemptId(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception persistenceError) {
                // PENDING のままでも再試行可能。元の失敗と記録失敗を両方残す。
                log.error("受益者退会取消しの失敗記録に失敗: subscriptionId={}",
                        target.subscriptionId(), persistenceError);
            }
            log.error("受益者退会時のStripe即時取消しに失敗: subscriptionId={}", target.subscriptionId(), e);
            return false;
        }
    }
}
