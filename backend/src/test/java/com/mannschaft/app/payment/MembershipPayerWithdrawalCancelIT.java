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
 * プロキシ</b>で検証する統合テスト（AC-13 の受け入れ／Codex 検分1巡目 P1-1〜3・2巡目 P1-1〜3 の是正）。
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
 *   <li>検分1巡目 P1-2: <b>先行契約の commit 後に後続契約の DB flush が失敗しても、先行が巻き戻らない</b></li>
 *   <li>検分1巡目 P1-1: 失敗はログではなく DB の処理状態として残り、再試行対象として拾える</li>
 *   <li>同時 webhook: 確定直前に {@code CANCELLED} が入っていたら ACTIVE へ<b>戻さない</b></li>
 *   <li>検分2巡目 P1-1: 退会取消が先に確定していれば、遅れて届いた退会イベントは何も作らない（逆順実行）</li>
 *   <li>検分2巡目 P1-1: 退会取消の<b>後</b>に本人が明示解約した契約は、遅れて届いた復旧処理が解除しない</li>
 *   <li>検分2巡目 P1-2: 作業行は Stripe に触れる前に<b>全件</b>確定し、退会状態からも再構築できる</li>
 *   <li>検分2巡目 P1-3: 復旧の途中停止は {@code RESTORING} として残り、非終端＝再試行対象になる</li>
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
    /**
     * MySQL の DATE 上限（9999-12-31）を超える期末。tx③ の flush を<b>実 DB で</b>失敗させるために使う
     * （検分2巡目 P2: モックではなく本物の flush/commit 失敗を再現する）。
     */
    private static final long OUT_OF_RANGE_PERIOD_END_EPOCH = 253_402_300_800L;

    @Autowired private MembershipSubscriptionService membershipSubscriptionService;
    @Autowired private MembershipPayerWithdrawalTxService payerWithdrawalTxService;
    @Autowired private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Autowired private MembershipPayerWithdrawalCancellationRepository cancellationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private com.mannschaft.app.auth.service.UserService userService;
    @Autowired private com.mannschaft.app.auth.repository.UserRepository userRepository;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private StripePaymentProvider stripePaymentProvider;
    /**
     * 退会取消は本番経路 {@code UserService#cancelWithdrawal()} を通すため
     * {@code WithdrawalCancelledEvent} が実際に発行される。そのリスナーは {@code @Async} で走るので、
     * 本 IT の検証対象（payment 側のロジック）と競合して非決定になる。ハンドラ自体は
     * {@code WithdrawalStripeHandlerTest} が検証済みであるため、ここでは無効化して
     * サービスメソッドを明示的に駆動する。
     */
    @MockitoBean private com.mannschaft.app.gdpr.service.WithdrawalStripeHandler withdrawalStripeHandler;

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
        // 退会処理は「退会申請中である」ことを処理時点の DB 真値で確かめる（検分2巡目 P1-1）。
        // 本番では UserService#requestWithdrawal が先に commit する状態を、ここで作る。
        markWithdrawing(payerUserId);
        markWithdrawing(otherPayerUserId);
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

    // ════════════════════════════════════════════════════════════════
    // AC-13: 対象の選別と反映
    // ════════════════════════════════════════════════════════════════

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

        assertThat(record(active)).isPresent()
                .get().satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUCCEEDED);
                    assertThat(r.getScheduledAt()).isNotNull();
                    assertThat(r.getRestoredAt()).isNull();
                    // 世代（どの退会試行の作業行か）が刻まれている（検分2巡目 P1-1）。
                    assertThat(r.getWithdrawalAttemptAt()).isNotNull();
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

    // ════════════════════════════════════════════════════════════════
    // 検分1巡目 P1-2: 巻き添えロールバックの排除
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-2 核心: 先行契約の commit 後に後続契約の DB flush が失敗しても、先行は巻き戻らない")
    void 失敗隔離_後続契約のDB失敗で先行契約が巻き戻らない() {
        // 対象の処理順は findIdsByPayerUserIdAndStatusIn（created_at DESC）に従う。
        // 「先行契約が commit 済みになった後に後続が失敗する」順序を作るため、ng を先に挿入して
        // ok を後に挿入する（DESC なので ok が先に処理される）。
        UUID ng = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_ng");
        UUID ok = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_ok");
        // Stripe は成功させ、tx③ の DB 反映だけを【実 DB の制約で】失敗させる。
        // MySQL の DATE 上限を超える期末を返させると saveAndFlush が本当に落ちる（モック例外ではない）。
        when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_it_ng"), anyString()))
                .thenReturn(new StripePaymentProvider.SubscriptionInfo(
                        "sub_it_ng", "active", OUT_OF_RANGE_PERIOD_END_EPOCH));

        List<UUID> scheduled = membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        // 是正前は全件が単一 REQUIRES_NEW だったため、ng の flush 失敗で ok まで巻き戻っていた。
        assertThat(scheduled).containsExactly(ok);
        assertThat(reload(ok).getCancelAtPeriodEnd()).isTrue();
        assertThat(reload(ng).getCancelAtPeriodEnd()).isFalse();

        assertThat(record(ok)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUCCEEDED));
        assertThat(record(ng)).isPresent().get().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.FAILED);
            assertThat(r.getLastError()).isNotBlank();
        });
    }

    @Test
    @DisplayName("P1-1: Stripe 失敗は FAILED として DB に残り、再試行対象（非終端）として拾える")
    void 失敗永続化_Stripe失敗が再試行対象になる() {
        // 同上（created_at DESC で ok が先に処理されるよう ng を先に挿入する）。
        UUID ng = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_s_ng");
        UUID ok = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_s_ok");
        when(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_it_s_ng"), anyString()))
                .thenThrow(new IllegalStateException("Stripe 障害"));

        assertThat(membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId))
                .containsExactly(ok);

        assertThat(record(ng)).isPresent().get().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.FAILED);
            assertThat(r.getAttemptCount()).isEqualTo(1);
        });
        assertThat(nonTerminalRecords())
                .extracting(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .contains(ng).doesNotContain(ok);
    }

    // ════════════════════════════════════════════════════════════════
    // 検分2巡目 P1-2: 作業行が「作られない」穴
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-2: 作業行は Stripe に触れる前に対象【全件】が確定している")
    void 先行永続化_Stripe前に全件の作業行が残る() {
        UUID a = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_r_a");
        UUID b = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_r_b");

        List<UUID> reserved = payerWithdrawalTxService.reserveAll(List.of(a, b), payerUserId);

        assertThat(reserved).containsExactlyInAnyOrder(a, b);
        // reserveAll は独立トランザクションで commit 済みでなければならない（ここで DB から読み直す）。
        assertThat(record(a)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.PENDING));
        assertThat(record(b)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.PENDING));
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("P1-2: 作業行が1件も無くても、退会状態を起点に未処理の解約を再構築できる")
    void 照合_作業行が無くても退会状態から拾い直せる() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_backlog");
        // 「非同期タスクが始まる前にプロセスが止まった」状態＝作業行が1件も無い。
        assertThat(record(target)).isEmpty();

        assertThat(membershipSubscriptionService.findWithdrawalCancelBacklog()).contains(target);

        // 解約が済めば backlog から外れる。
        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
        assertThat(membershipSubscriptionService.findWithdrawalCancelBacklog()).doesNotContain(target);
    }

    // ════════════════════════════════════════════════════════════════
    // 検分2巡目 P1-1: イベントの逆順・本人の新しい意思
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-1 逆順: 退会取消が先に確定していれば、遅れて届いた退会イベントは何もしない")
    void 逆順_退会取消後に届いた退会イベントは解約を作らない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_late");
        markNotWithdrawing(payerUserId);

        assertThat(membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId)).isEmpty();

        assertThat(reload(target).getCancelAtPeriodEnd()).isFalse();
        assertThat(record(target)).isEmpty();
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("P1-1 再退会: 再び退会申請中なら、遅れて届いた取消イベントは解約を解除しない")
    void 逆順_再退会中は遅延した取消イベントで復旧しない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_regen");
        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();

        // 取消 → 再退会（deleted_at が再び入っている）。ここへ前回退会ぶんの取消イベントが遅れて届く。
        markNotWithdrawing(payerUserId);
        markWithdrawing(payerUserId);

        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();
        verify(stripePaymentProvider, never())
                .revertSubscriptionCancelAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("P1-1: 退会取消の【後】に本人が明示解約した契約は、遅れて届いた復旧処理が解除しない")
    void 本人意思_取消後の明示解約は復旧で解除されない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_selfagain");
        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);

        // 退会取消。ただし復旧イベントはまだ届いていない。
        markNotWithdrawing(payerUserId);
        // その間に本人が「やはり自分で解約する」と決めた（＝新しい人間の判断）。
        // 明示解約は一度予約を戻してから行う（実運用では復旧が先に走っている状況に相当）。
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "UPDATE membership_subscriptions SET cancel_at_period_end = false "
                                        + "WHERE id = UNHEX(:id)")
                        .setParameter("id", hex(target)).executeUpdate());
        membershipSubscriptionService.cancel(target, payerUserId);
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();

        // ここで遅れて退会取消の復旧処理が走る。本人の新しい意思を覆してはならない。
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUPERSEDED));
    }

    // ════════════════════════════════════════════════════════════════
    // 退会取消の正常系と、その途中停止（検分2巡目 P1-3）
    // ════════════════════════════════════════════════════════════════

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

        markNotWithdrawing(payerUserId);
        List<UUID> restored = membershipSubscriptionService
                .restoreAllForPayerOnWithdrawalCancelled(payerUserId);

        assertThat(restored).containsExactly(byWithdrawal);
        assertThat(reload(byWithdrawal).getCancelAtPeriodEnd()).isFalse();
        // 本人の意思による解約は退会取消では戻さない（boolean だけでは区別できなかった点）。
        assertThat(reload(bySelf).getCancelAtPeriodEnd()).isTrue();
        assertThat(record(byWithdrawal)).isPresent().get().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(MembershipPayerWithdrawalCancellationStatus.RESTORED);
            assertThat(r.getRestoredAt()).isNotNull();
        });

        // 二重復旧は起こらない（RESTORED は復旧対象から外れる）。
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
    }

    @Test
    @DisplayName("P1-3: 復旧の途中で止まっても RESTORING として残り、非終端＝再試行対象になる")
    void 復旧途中停止_RESTORINGで残り再試行対象になる() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_restoring");
        membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
        markNotWithdrawing(payerUserId);

        // tx①（復旧着手）まで進んだところでプロセスが止まった状態を作る。
        assertThat(payerWithdrawalTxService.prepareRestore(target, payerUserId)).isPresent();

        // 是正前はここが SUCCEEDED のままで、再試行対象（PENDING/FAILED）に入らず永久に取り残された。
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.RESTORING));
        assertThat(nonTerminalRecords())
                .extracting(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .contains(target);

        // 取消イベントの再処理で RESTORING の行を拾い直し、最後まで完了できる。
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .containsExactly(target);
        assertThat(reload(target).getCancelAtPeriodEnd()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // 競合・冪等
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同時 webhook: 確定直前に CANCELLED が入っていたら ACTIVE へ戻さない")
    void 競合_webhookのCANCELLEDを上書きしない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_race");

        // tx①（予約着手）まで進めた状態を作る。
        Optional<MembershipPayerWithdrawalTxService.PreparedTarget> prepared =
                payerWithdrawalTxService.prepare(target, payerUserId);
        assertThat(prepared).isPresent();

        // ここで customer.subscription.deleted webhook が先に確定したと仮定する。
        transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "UPDATE membership_subscriptions SET status = 'CANCELLED' WHERE id = UNHEX(:id)")
                        .setParameter("id", hex(target)).executeUpdate());

        MembershipPayerWithdrawalTxService.ApplyOutcome outcome = payerWithdrawalTxService.applyScheduled(
                target, payerUserId, prepared.get().withdrawalAttemptId(), PERIOD_END_EPOCH);

        assertThat(outcome).isEqualTo(MembershipPayerWithdrawalTxService.ApplyOutcome.SKIPPED);
        assertThat(reload(target).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        assertThat(reload(target).getCancelAtPeriodEnd()).isFalse();

        // 作業行は【自分たちのもの】（世代一致かつ PENDING）なので SUCCEEDED で終端化する。
        // webhook が先に CANCELLED を確定させただけであり「課金を止める」目的は達成されている。
        // 検分3巡目 P1-3 の「applied=false を SUCCEEDED にしない」は、
        // 【自分が予約したのではない】場合（本人の明示解約 = SUPERSEDED 済みの行）に向けた規則であり、
        // ここには当たらない。その安全性はこのテスト自身が下で直接確かめる。
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUCCEEDED));

        // 【安全性の直接検証】終端化した契約を、退会取消の復旧が蘇らせないこと。
        markNotWithdrawing(payerUserId);
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
        assertThat(reload(target).getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
        // 復旧できない行は非終端のまま残さず SUPERSEDED へ降ろす（照合バッチが永久に拾わない）。
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUPERSEDED));
    }

    @Test
    @DisplayName("P1-2 窓: Stripe 呼び出し中に退会が取り消されたら SUCCEEDED にせず RESTORING で残す")
    void 世代変化_Stripe中の退会取消は確定させない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_window");

        // tx① を終えた（＝作業行は PENDING、ロックは解放済み）状態。
        Optional<MembershipPayerWithdrawalTxService.PreparedTarget> prepared =
                payerWithdrawalTxService.prepare(target, payerUserId);
        assertThat(prepared).isPresent();

        // Stripe を呼んでいる最中に退会が取り消された（本番経路を通す）。
        markNotWithdrawing(payerUserId);

        MembershipPayerWithdrawalTxService.ApplyOutcome outcome = payerWithdrawalTxService.applyScheduled(
                target, payerUserId, prepared.get().withdrawalAttemptId(), PERIOD_END_EPOCH);

        // 是正前はここで DB へ反映し SUCCEEDED を確定させていた。ユーザーは退会中でないため
        // backlog にも入らず、再試行対象にもならず、回復不能だった。
        assertThat(outcome).isEqualTo(
                MembershipPayerWithdrawalTxService.ApplyOutcome.ABORTED_GENERATION_CHANGED);
        assertThat(reload(target).getCancelAtPeriodEnd()).isFalse();
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.RESTORING));
        // 非終端なので照合バッチが拾える。
        assertThat(nonTerminalRecords())
                .extracting(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .contains(target);
    }

    @Test
    @DisplayName("P1-3 競合: PENDING の最中に「退会取消＋本人の明示解約」が入っても本人の意思を覆さない")
    void 競合_PENDING中の明示解約は復旧で解除されない() {
        UUID target = insertSubscription(payerUserId, MembershipSubscriptionStatus.ACTIVE, "sub_it_pendingrace");

        // 古い退会処理が tx① を終えた直後（作業行は PENDING）。
        assertThat(payerWithdrawalTxService.prepare(target, payerUserId)).isPresent();

        // そこへ「退会取消 → 本人が自分の意思で解約」が入る。
        markNotWithdrawing(payerUserId);
        membershipSubscriptionService.cancel(target, payerUserId);

        // 是正前は SUPERSEDED 化が PENDING を対象外にしていたため no-op で、
        // その後の復旧が本人の明示解約を解除していた。
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUPERSEDED));
        assertThat(membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId))
                .isEmpty();
        assertThat(reload(target).getCancelAtPeriodEnd()).isTrue();
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
        // 再送で SUCCEEDED を PENDING へ差し戻していないこと（同一世代の確定済みは触らない）。
        assertThat(record(target)).isPresent().get().satisfies(r ->
                assertThat(r.getStatus())
                        .isEqualTo(MembershipPayerWithdrawalCancellationStatus.SUCCEEDED));
    }

    // ════════════════════════════════════════════════════════════════
    // フィクスチャ / DB 実値の読み出し
    // ════════════════════════════════════════════════════════════════

    /**
     * 退会申請中にする。{@code UserEntity#requestDeletion()}（本番と同じドメインメソッド）を通す。
     *
     * <p>{@code UserService#requestWithdrawal} 全体はパスワード検証やレートリミットを含み IT には重いが、
     * 「{@code deleted_at} を立てて commit する」という本質は同じである。SQL 直叩きにしないのは、
     * 列名や意味づけの変更をテストが素通りさせないため。</p>
     */
    private void markWithdrawing(Long userId) {
        transactionTemplate.executeWithoutResult(tx -> {
            UserEntity user = userRepository.findById(userId).orElseThrow();
            user.requestDeletion();
            userRepository.saveAndFlush(user);
        });
    }

    /**
     * 退会を取り消す。<b>本番経路 {@code UserService#cancelWithdrawal()} をそのまま通す</b>。
     *
     * <p>是正前はここが {@code UPDATE users SET deleted_at = NULL} の SQL 直叩きだったため、
     * <b>本番の取消経路が {@code @SQLRestriction} に阻まれて {@code AUTH_015} で終了し、
     * {@code WithdrawalCancelledEvent} に到達しない</b>という欠陥をテストが完全に隠していた
     * （Codex 検分3巡目 P1-1）。テストは本番経路を通さなければ意味がない。</p>
     */
    private void markNotWithdrawing(Long userId) {
        userService.cancelWithdrawal(userId);
    }

    private List<MembershipPayerWithdrawalCancellationEntity> nonTerminalRecords() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return cancellationRepository.findByStatusInOrderByUpdatedAtAsc(
                    MembershipPayerWithdrawalCancellationStatus.NON_TERMINAL);
        });
    }

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
