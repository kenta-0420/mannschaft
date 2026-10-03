package com.mannschaft.app.payment;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.gdpr.service.WithdrawalStripeHandler;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.repository.MembershipBeneficiaryWithdrawalCancellationRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.service.MembershipBeneficiaryWithdrawalTxService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.MembershipSubscriptionWebhookService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-011 AC-1: 受益者退会の即時取消しを実 MySQL と Spring の独立コミットで検証する。
 * Stripe 通信だけをモックし、DB 先行終端・未着手回収・世代・Webhook 競合を実 Repository で固定する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-011 受益者退会の即時取消し（実 MySQL）")
class MembershipBeneficiaryWithdrawalCancelIT extends AbstractMySqlIntegrationTest {
    @Autowired private MembershipSubscriptionService service;
    @Autowired private MembershipBeneficiaryWithdrawalTxService txService;
    @Autowired private MembershipSubscriptionWebhookService webhookService;
    @Autowired private MembershipSubscriptionRepository subscriptions;
    @Autowired private MembershipBeneficiaryWithdrawalCancellationRepository cancellations;
    @Autowired private UserRepository users;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactions;
    @PersistenceContext private EntityManager entityManager;
    @MockitoBean private StripePaymentProvider stripe;
    // 本テストは payment の処理順を制御する。非同期配送そのものは handler の単体テストで固定する。
    @MockitoBean private WithdrawalStripeHandler withdrawalStripeHandler;

    private final List<UUID> subscriptionIds = new ArrayList<>();
    private Long payerId;
    private Long beneficiaryId;
    private Long otherBeneficiaryId;

    @BeforeEach
    void setUp() {
        Mockito.reset(stripe);
        payerId = insertUser("payer");
        beneficiaryId = insertUser("beneficiary");
        otherBeneficiaryId = insertUser("other");
        withdraw(beneficiaryId);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(tx -> {
            for (UUID id : subscriptionIds) {
                cancellations.findBySubscriptionId(id).ifPresent(cancellations::delete);
                subscriptions.deleteById(id);
            }
            // 退会済みユーザーは SQLRestriction で除外されるため、検体 ID だけを native で削除する。
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", List.of(payerId, beneficiaryId, otherBeneficiaryId)).executeUpdate();
        });
        subscriptionIds.clear();
    }

    @Test
    void 同一payerの別受益者を維持しACTIVEとPAST_DUEだけ即時取消する() {
        UUID active = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        UUID pastDue = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.PAST_DUE);
        UUID pending = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.PENDING);
        UUID cancelled = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.CANCELLED);
        UUID expired = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.EXPIRED);
        UUID sibling = insertSubscription(otherBeneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        transactions.executeWithoutResult(tx -> {
            MembershipSubscriptionEntity subscription = subscriptions.findById(active).orElseThrow();
            subscription.scheduleCancelAtPeriodEnd();
            subscriptions.saveAndFlush(subscription);
        });

        assertThat(service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId))
                .containsExactlyInAnyOrder(active, pastDue);

        assertThat(reload(active).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(reload(pastDue).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(reload(active).getCancelAtPeriodEnd()).isFalse();
        assertThat(reload(pending).getStatus()).isEqualTo(MembershipSubscriptionStatus.PENDING);
        assertThat(reload(sibling).getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
        for (UUID untouched : List.of(pending, cancelled, expired, sibling)) {
            assertThat(cancellations.findBySubscriptionId(untouched)).isEmpty();
            verify(stripe, never()).cancelBillingSubscriptionImmediately(eq(stripeId(untouched)), anyString());
        }
        assertThat(record(active).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
    }

    @Test
    void Stripe失敗時もDBは先行取消済みで同じキーを再試行する() {
        UUID id = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        doAnswer(invocation -> {
            assertThat(reload(id).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
            assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.PENDING);
            throw new IllegalStateException("Stripe 一時障害");
        }).when(stripe).cancelBillingSubscriptionImmediately(eq(stripeId(id)), anyString());

        assertThat(service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId)).isEmpty();
        assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.FAILED);
        assertThat(record(id).getLastError()).contains("Stripe 一時障害");

        doNothing().when(stripe).cancelBillingSubscriptionImmediately(eq(stripeId(id)), anyString());
        service.retryBeneficiaryWithdrawalCancellations();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(stripe, times(2)).cancelBillingSubscriptionImmediately(eq(stripeId(id)), keys.capture());
        assertThat(keys.getAllValues()).containsOnly(keys.getValue());
        assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
    }

    @Test
    void 作業行作成後と作成前の停止を両方再試行で回収する() {
        UUID prepared = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        UUID notStarted = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.PAST_DUE);
        assertThat(txService.reserveAndCancel(prepared, beneficiaryId)).isPresent();
        // Stripe 呼出前にプロセスが停止した状態。後続契約には作業行すら存在しない。
        assertThat(record(prepared).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.PENDING);
        assertThat(cancellations.findBySubscriptionId(notStarted)).isEmpty();

        service.retryBeneficiaryWithdrawalCancellations();

        for (UUID id : List.of(prepared, notStarted)) {
            assertThat(reload(id).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
            assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
            verify(stripe).cancelBillingSubscriptionImmediately(eq(stripeId(id)), anyString());
        }
    }

    @Test
    void 退会取消後に遅着したイベントは契約を取り消さない() {
        UUID id = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        userService.cancelWithdrawal(beneficiaryId);

        assertThat(service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId)).isEmpty();

        assertThat(reload(id).getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
        assertThat(cancellations.findBySubscriptionId(id)).isEmpty();
        verify(stripe, never()).cancelBillingSubscriptionImmediately(anyString(), anyString());
    }

    @Test
    void 退会取消と再退会でも旧契約の同期は元世代で継続し新契約だけ新世代になる() {
        UUID old = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        assertThat(txService.reserveAndCancel(old, beneficiaryId)).isPresent();
        UUID oldAttempt = record(old).getWithdrawalAttemptId();
        userService.cancelWithdrawal(beneficiaryId);
        UUID current = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        withdraw(beneficiaryId);

        service.retryBeneficiaryWithdrawalCancellations();

        assertThat(record(old).getWithdrawalAttemptId()).isEqualTo(oldAttempt);
        assertThat(record(current).getWithdrawalAttemptId()).isNotEqualTo(oldAttempt);
        verify(stripe).cancelBillingSubscriptionImmediately(stripeId(old),
                "withdrawal-beneficiary-cancel-" + old + "-" + oldAttempt);
        assertThat(reload(old).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        // 古い世代の遅い失敗報告は新しい世代の結果へ干渉しない。
        txService.markFailed(current, oldAttempt, "旧世代の遅延失敗");
        assertThat(record(current).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
    }

    @Test
    void Stripe成功直後の停止は同じキーで回収し遅い並行失敗で成功を戻さない() {
        UUID id = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        var target = txService.reserveAndCancel(id, beneficiaryId).orElseThrow();
        String key = "withdrawal-beneficiary-cancel-" + id + "-" + target.withdrawalAttemptId();
        stripe.cancelBillingSubscriptionImmediately(stripeId(id), key);
        // Stripe 成功後、DB の成功記録前にクラッシュ。
        service.retryBeneficiaryWithdrawalCancellations();
        verify(stripe, times(2)).cancelBillingSubscriptionImmediately(stripeId(id), key);

        txService.markFailed(id, target.withdrawalAttemptId(), "並行した古い呼出の遅延失敗");

        assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
        assertThat(record(id).getLastError()).isNull();
    }

    @Test
    void Webhookが先着して終端化していれば取消要求を再発行しない() {
        UUID id = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        deletedWebhook(id);

        assertThat(service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId)).isEmpty();

        assertThat(reload(id).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(cancellations.findBySubscriptionId(id)).isEmpty();
        verify(stripe, never()).cancelBillingSubscriptionImmediately(anyString(), anyString());
    }

    @Test
    void Stripe呼出中の並行Webhookと重複退会でもDB終端と作業行を維持する() throws Exception {
        UUID id = insertSubscription(beneficiaryId, MembershipSubscriptionStatus.ACTIVE);
        CountDownLatch stripeEntered = new CountDownLatch(1);
        CountDownLatch releaseStripe = new CountDownLatch(1);
        doAnswer(invocation -> {
            stripeEntered.countDown();
            assertThat(releaseStripe.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(stripe).cancelBillingSubscriptionImmediately(eq(stripeId(id)), anyString());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var initial = executor.submit(() -> service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId));
            try {
                assertThat(stripeEntered.await(20, TimeUnit.SECONDS)).isTrue();
                deletedWebhook(id);
                assertThat(service.cancelAllForBeneficiaryOnWithdrawal(beneficiaryId)).isEmpty();
            } finally {
                releaseStripe.countDown();
            }
            assertThat(initial.get(20, TimeUnit.SECONDS)).containsExactly(id);
        }

        assertThat(reload(id).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(record(id).getStatus()).isEqualTo(MembershipBeneficiaryWithdrawalCancellationStatus.SUCCEEDED);
        verify(stripe).cancelBillingSubscriptionImmediately(eq(stripeId(id)), anyString());
    }

    private void deletedWebhook(UUID subscriptionId) {
        String payload = "deleted-" + UUID.randomUUID();
        when(stripe.constructInvoiceEvent(payload, "test-signature")).thenReturn(
                new StripePaymentProvider.InvoiceWebhookEventInfo("evt_" + UUID.randomUUID(),
                        "customer.subscription.deleted", false, stripeId(subscriptionId),
                        null, null, null, null, null, null, null, null));
        webhookService.handleWebhook(payload, "test-signature");
    }

    private Long insertUser(String suffix) {
        return transactions.execute(tx -> users.saveAndFlush(UserEntity.builder()
                .email("beneficiary-withdrawal-" + suffix + "-" + UUID.randomUUID() + "@example.com")
                .lastName("退会").firstName(suffix).displayName("退会 " + suffix)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build()).getId());
    }

    private void withdraw(Long userId) {
        transactions.executeWithoutResult(tx -> {
            UserEntity user = users.findById(userId).orElseThrow();
            user.requestDeletion();
            users.saveAndFlush(user);
        });
    }

    private UUID insertSubscription(Long beneficiary, MembershipSubscriptionStatus status) {
        UUID id = transactions.execute(tx -> {
            MembershipSubscriptionEntity entity = subscriptions.saveAndFlush(MembershipSubscriptionEntity.builder()
                    .paymentItemId(900_412L).beneficiaryUserId(beneficiary).payerUserId(payerId)
                    .scopeKind(ScopeKind.TEAM).scopeId(900_411L).payeeConnectAccountId(UUID.randomUUID())
                    .billingInterval(BillingInterval.MONTHLY).status(status).feePolicyKey("DEFAULT")
                    .faceAmount(3000).currency("JPY").cancelAtPeriodEnd(false).build());
            entity.linkStripeIds(stripeId(entity.getId()), "cus_beneficiary_withdrawal");
            return subscriptions.saveAndFlush(entity).getId();
        });
        subscriptionIds.add(id);
        return id;
    }

    private MembershipSubscriptionEntity reload(UUID id) {
        return subscriptions.findById(id).orElseThrow();
    }

    private MembershipBeneficiaryWithdrawalCancellationEntity record(UUID id) {
        return cancellations.findBySubscriptionId(id).orElseThrow();
    }

    private String stripeId(UUID id) {
        return "sub_bw_" + id;
    }
}
