package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.service.WithdrawalStateQueryService;
import com.mannschaft.app.auth.service.WithdrawalStateQueryService.WithdrawalAttempt;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.repository.MembershipBeneficiaryWithdrawalCancellationRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 受益者退会の取消しを独立コミットする。
 * auth の退会状態と payment の取消しを線形化するため、Service 経由でユーザー行をロックする。
 * ロック順は既存の払い手退会と同じ users → subscriptions → 作業行。
 */
@Service
@RequiredArgsConstructor
public class MembershipBeneficiaryWithdrawalTxService {
    private static final List<MembershipSubscriptionStatus> CANCELLABLE =
            List.of(MembershipSubscriptionStatus.ACTIVE, MembershipSubscriptionStatus.PAST_DUE);
    private final MembershipSubscriptionRepository subscriptionRepository;
    private final MembershipBeneficiaryWithdrawalCancellationRepository cancellationRepository;
    private final WithdrawalStateQueryService withdrawalStateQueryService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Target> reserveAndCancel(UUID subscriptionId, Long beneficiaryUserId) {
        Optional<WithdrawalAttempt> attempt = withdrawalStateQueryService
                .lockAndFindPendingWithdrawalAttempt(beneficiaryUserId);
        if (attempt.isEmpty()) {
            return Optional.empty();
        }
        Optional<MembershipSubscriptionEntity> locked = subscriptionRepository.findByIdForUpdate(subscriptionId);
        if (locked.isEmpty() || !beneficiaryUserId.equals(locked.get().getBeneficiaryUserId())
                || !CANCELLABLE.contains(locked.get().getStatus())) {
            return Optional.empty();
        }
        MembershipSubscriptionEntity subscription = locked.get();
        MembershipBeneficiaryWithdrawalCancellationEntity row = cancellationRepository
                .findBySubscriptionIdForUpdate(subscriptionId).orElseGet(() ->
                        MembershipBeneficiaryWithdrawalCancellationEntity.builder()
                                .subscriptionId(subscriptionId).beneficiaryUserId(beneficiaryUserId)
                                .withdrawalAttemptId(attempt.get().attemptId())
                                .stripeSubscriptionId(subscription.getStripeSubscriptionId())
                                .status(MembershipBeneficiaryWithdrawalCancellationStatus.PENDING).build());
        subscription.markCancelled();
        if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            subscription.clearCancelAtPeriodEnd();
        }
        subscriptionRepository.saveAndFlush(subscription);
        cancellationRepository.saveAndFlush(row);
        return Optional.of(new Target(subscriptionId, row.getStripeSubscriptionId(), row.getWithdrawalAttemptId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Target> reserveRetry(UUID subscriptionId) {
        return cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .filter(row -> row.getStatus() != MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED)
                .map(row -> new Target(row.getSubscriptionId(), row.getStripeSubscriptionId(), row.getWithdrawalAttemptId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID subscriptionId, UUID attemptId) {
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .filter(row -> attemptId.equals(row.getWithdrawalAttemptId())).ifPresent(row -> {
                    row.markSucceeded();
                    cancellationRepository.saveAndFlush(row);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID subscriptionId, UUID attemptId, String error) {
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .filter(row -> attemptId.equals(row.getWithdrawalAttemptId())).ifPresent(row -> {
                    row.markFailed(error);
                    cancellationRepository.saveAndFlush(row);
                });
    }

    public record Target(UUID subscriptionId, String stripeSubscriptionId, UUID withdrawalAttemptId) {}
}
