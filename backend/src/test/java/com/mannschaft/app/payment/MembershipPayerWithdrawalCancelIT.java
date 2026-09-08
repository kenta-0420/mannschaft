package com.mannschaft.app.payment;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.repository.MembershipPayerWithdrawalCancellationRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.service.MembershipPayerWithdrawalTxService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 柱③-B（CMP-260901-1538・PR-3）: 払い手退会に伴う継続課金の一括期末解約を<b>実 MySQL・実 Spring
 * プロキシ</b>で検証する統合テスト（AC-13 の受け入れ／Codex 検分1巡目 P1-1・P1-2・P1-3 の是正）。
 *
 * <h2>なぜ UT では足りないのか</h2>
 * <p>Mockito 単体テストは Spring AOP・{@code REQUIRES_NEW}・flush/commit・実 Repository 検索・
 * {@code AFTER_COMMIT} 配送のいずれも通らない。本 PR の是正はまさにその<b>トランザクション境界</b>
 * そのものであるため、実 DB でなければ意味を持たない。</p>
 *
 * <h2>検証範囲</h2>
 * <ul>
 *   <li>AC-13: {@code payer_user_id} 一致かつ {@code ACTIVE}/{@code PAST_DUE} のみを期末解約する</li>
 *   <li>複数 payer の隔離: 他人が払い手の継続課金には一切触れない</li>
 *   <li>P1-2: 1件の Stripe 失敗が、<b>既に commit 済みの他契約を巻き戻さない</b></li>
 *   <li>P1-1: 失敗はログではなく DB の処理状態として残り、再試行対象として拾える</li>
 *   <li>同時 webhook: 確定直前に {@code CANCELLED} が入っていたら ACTIVE へ<b>戻さない</b></li>
 *   <li>P1-3: 退会取消で退会処理由来の予約だけを解除し、<b>本人が明示解約した契約は復活させない</b></li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} を付けない。付けるとテスト側の tx が全体を包み、
 * 検証したい {@code REQUIRES_NEW} の独立性そのものが観測できなくなるためである。
 * フィクスチャ投入も検証も {@link TransactionTemplate} で tx を区切り、読み出し前に
 * {@link EntityManager#clear()} して DB の実値を読む。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-B 払い手退会に伴う継続課金の一括期末解約（実 MySQL）")
class MembershipPayerWithdrawalCancelIT extends AbstractMySqlIntegrationTest {

    private static final long SCOPE_ID = 900_311L;
    private static final long PAYMENT_ITEM_ID = 900_312L;
    /** Stripe が返す期末（2027-01-31T00:00:00Z 相当の unix 秒）。 */
    private static final long PERIOD_END_EPOCH = 1_801_440_000L;

    @Autowired private MembershipSubscriptionService membershipSubscriptionService;
    @Autowired private MembershipPayerWithdrawalTxService payerWithdrawalTxService;
    @Autowired private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Autowired private MembershipPayerWithdrawalCancellationRepository cancellationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private StripePaymentProvider stripePaymentProvider;

    private Long payerUserId;
    private Long otherPayerUserId;
    private Long beneficiaryUserId;

    @BeforeEach
    void setUp() {
        Mockito.reset(stripePaymentProvider);
        when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(anyString(), anyString()))
                .thenAnswer(inv -> new StripePaymentProvider.SubscriptionInfo(
                        inv.getArgument(0), "active", PERIOD_END_EPOCH));
        when(stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(anyString(), anyString()))
                .thenAnswer(inv -> new StripePaymentProvider.SubscriptionInfo(
                        inv.getArgument(0), "active", PERIOD_END_EPOCH));

        transactionTemplate.executeWithoutResult(tx -> {
            payerUserId = insertUser("payer");
            otherPayerUserId = insertUser("other-payer");
            beneficiaryUserId = insertUser("beneficiary");
            entityManager.flush();
            entityManager.clear();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM membership_payer_withdrawal_cancellations "
                                    + "WHERE payer_user_id IN (:a, :b)")
                    .setParameter("a", payerUserId).setParameter("b", otherPayerUserId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM membership_subscriptions WHERE payer_user_id IN (:a, :b)")
                    .setParameter("a", payerUserId).setParameter("b", otherPayerUserId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:a, :b, :c)")
                    .setParameter("a", payerUserId).setParameter("b", otherPayerUserId)
                    .setParameter("c", beneficiaryUserId).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC-13: payer 一致の ACTIVE/PAST_DUE だけを期末解約し、処理状態を SUCCEEDED で残す")
    void 正常_対象だけを期末解約し処理状態を残す() {
        UUID active = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_active");
        UUID pastDue = insertSubscription(payerUserId, MembershipSubscriptionStatus.PAST_DUE, "sub_it_pastdue");
        UUID pending = insertSubscription(payerUserId, MembershipSubscriptionStatus.PENDING, "sub_it_pending");
        UUID cancelled = insertSubscription(payerUserId, MembershipSubscriptionStatus.CANCELLED, "sub_it_cancelled");

        List<UUID> scheduled = membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        assertThat(scheduled).containsExactlyInAnyOrder(active, pastDue);
        assertThat(reload(active).getCancelAtPeriodEnd()).isTrue();
        assertThat(reload(pastDue).getCancelAtPeriodEnd()).isTrue();
        // PENDING（初回課金前）と終端は対象外。Stripe も叩かない。
        assertThat(reload(pending).getCancelAtPeriodEnd()).isFalse();
        assertThat(reload(cancelled).getCancelAtPeriodEnd()).isFalse();
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(eq("sub_it_pending"), anyString());
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(eq("sub_it_cancelled"), anyString());

        // Stripe が返した期末が DB に反映されている。
        assertThat(reload(active).getCurrentPeriodEnd())
                .isEqualTo(LocalDate.ofInstant(Instant.ofEpochSecond(PERIOD_END_EPOCH),
                        com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE));

        // 処理状態が SUCCEEDED で永続化されている（P1-1: 再試行の拾い直しの土台）。
        assertThat(record(active)).isPresent()
                .get().satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUCCEEDED);
                    assertThat(r.getScheduledAt()).isNotNull();
                    assertThat(r.getRestoredAt()).isNull();
                });
        assertThat(record(pending)).isEmpty();
    }

    @Test
    @DisplayName("隔離: 他人が払い手の継続課金には一切触れない")
    void 隔離_他payerのサブスクは無傷() {
        UUID mine = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_mine");
        UUID theirs = insertSubscription(otherPayerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_theirs");

        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        assertThat(reload(mine).getCancelAtPeriodEnd()).isTrue();
        assertThat(reload(theirs).getCancelAtPeriodEnd()).isFalse();
        assertThat(record(theirs)).isEmpty();
    }

    @Test
    @DisplayName("P1-2: 1件の Stripe 失敗が、既に commit 済みの他契約を巻き戻さない")
    void 失敗隔離_1件の失敗で成功済みの契約が巻き戻らない() {
        UUID ok = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_ok");
        UUID ng = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_ng");
        when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_it_ng"), anyString()))
                .thenThrow(new IllegalStateException("Stripe 障害"));

        List<UUID> scheduled = membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        assertThat(scheduled).containsExactly(ok);
        // 是正前は、全件が単一 REQUIRES_NEW だったため成功済みの ok まで巻き戻っていた。
        assertThat(reload(ok).getCancelAtPeriodEnd()).isTrue();
        assertThat(reload(ng).getCancelAtPeriodEnd()).isFalse();

        // P1-1: 失敗はログではなく DB に残り、再試行対象として拾える。
        assertThat(record(ng)).isPresent()
                .get().satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.FAILED);
                    assertThat(r.getLastError()).isNotBlank();
                    assertThat(r.getAttemptCount()).isEqualTo(1);
                });
        assertThat(cancellationRepository.findByStatusInAndRestoredAtIsNullOrderByUpdatedAtAsc(
                List.of(MembershipPayerWithdrawalCancellationStatus.PENDING,
                        MembershipPayerWithdrawalCancellationStatus.FAILED)))
                .extracting(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .contains(ng);
    }

    @Test
    @DisplayName("同時 webhook: 確定直前に CANCELLED が入っていたら ACTIVE へ戻さない")
    void 競合_webhookのCANCELLEDを上書きしない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_race");

        // tx①（予約着手）まで進めた状態を作る。
        assertThat(payerWithdrawalTxService.prepare(target, payerUserId)).isPresent();

        // ここで customer.subscription.deleted webhook が先に確定したと仮定する。
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "UPDATE membership_subscriptions SET status = 'CANCELLED' WHERE id = UNHEX(:id)")
                    .setParameter("id", hex(target)).executeUpdate();
        });

        boolean applied = payerWithdrawalTxService.applyScheduled(target, payerUserId, PERIOD_END_EPOCH);

        assertThat(applied).isFalse();
        assertThat(reload(target).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(reload(target).getCancelAtPeriodEnd()).isFalse();
    }

    @Test
    @DisplayName("P1-3: 退会取消は退会処理由来の予約だけを解除し、本人が明示解約した契約は復活させない")
    void 退会取消_由来を区別して復旧する() {
        UUID byWithdrawal = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_wd");
        UUID bySelf = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_self");
        // 本人が退会前に明示解約した契約（処理状態の行を持たない）。
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "UPDATE membership_subscriptions SET cancel_at_period_end = true "
                                        + "WHERE id = UNHEX(:id)")
                        .setParameter("id", hex(bySelf)).executeUpdate());

        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
        assertThat(reload(byWithdrawal).getCancelAtPeriodEnd()).isTrue();

        List<UUID> restored = membershipSubscriptionService
                .restoreAllForPayerOnWithdrawalCancelled(payerUserId);

        assertThat(restored).containsExactly(byWithdrawal);
        assertThat(reload(byWithdrawal).getCancelAtPeriodEnd()).isFalse();
        // 本人の意思による解約は退会取消では戻さない（ここが boolean だけでは区別できなかった点）。
        assertThat(reload(bySelf).getCancelAtPeriodEnd()).isTrue();
        assertThat(record(byWithdrawal)).isPresent()
                .get().satisfies(r -> assertThat(r.getRestoredAt()).isNotNull());

        // 二重復旧は起こらない（restored_at 済みは対象から外れる）。
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
    }

    @Test
    @DisplayName("冪等: 同じ退会イベントが再送されても Stripe を叩き直さない")
    void 冪等_再送でStripeを再発行しない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_idem");

        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
        List<UUID> second = membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        assertThat(second).isEmpty();
        verify(stripePaymentProvider, Mockito.times(1))
                .cancelSubscriptionAtPeriodEnd(eq("sub_it_idem"), anyString());
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();
    }

    // ============================================================
    // フィクスチャ / DB 実値の読み出し
    // ============================================================

    private UUID insertSubscription(Long payer, MembershipSubscriptionStatus status, String stripeSubId) {
        return transactionTemplate.execute(tx -> {
            UUID id = membershipSubscriptionRepository.saveAndFlush(MembershipSubscriptionEntity.builder()
                    .paymentItemId(PAYMENT_ITEM_ID)
                    .beneficiaryUserId(beneficiaryUserId)
                    .payerUserId(payer)
                    .scopeKind(ScopeKind.TEAM)
                    .scopeId(SCOPE_ID)
                    .payeeConnectAccountId(UUID.randomUUID())
                    .billingInterval(BillingInterval.MONTHLY)
                    .status(status)
                    .feePolicyKey("DEFAULT")
                    .faceAmount(3_000)
                    .currency("JPY")
                    .stripeSubscriptionId(stripeSubId)
                    .cancelAtPeriodEnd(false)
                    .build()).getId();
            entityManager.clear();
            return id;
        });
    }

    private MembershipSubscriptionEntity reload(UUID id) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return membershipSubscriptionRepository.findById(id).orElseThrow();
        });
    }

    private Optional<MembershipPayerWithdrawalCancellationEntity> record(UUID subscriptionId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return cancellationRepository.findBySubscriptionId(subscriptionId);
        });
    }

    private Long insertUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("payer-withdrawal-" + suffix + "-" + System.nanoTime() + "@example.com")
                .lastName("退会").firstName(suffix).displayName("退会 " + suffix)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }
}
