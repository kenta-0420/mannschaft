package com.mannschaft.app.payment;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowLifecycleService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L7 — payment/escrow ドメインの通知トランザクション境界の実DB検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code EscrowLifecycleService} の各メソッドは {@code @Transactional(REQUIRES_NEW)} であり、
 * その内側から {@code EscrowNotificationService}（{@code @Transactional} 既定 {@code REQUIRED}）を
 * 直接呼び、さらにその先で {@link NotificationService#createNotification} が
 * {@code notifications} へ INSERT していた。INSERT が落ちると例外はそのまま業務トランザクションへ伝播し、
 * <b>Stripe 側の操作は成立したまま DB だけが巻き戻る</b>:</p>
 * <ul>
 *   <li>取消: {@code escrow_transactions.status} が CANCELLED に落ちず、{@code cancelled_at} も消える。</li>
 *   <li>HELD 昇格: 作成済み PaymentIntent の ID と PENDING_CONFIRMATION 遷移が消え、孤児 PI が残る。</li>
 * </ul>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService} を spy して {@code createNotification} を必ず失敗させ、業務行が
 * コミットされているかを別トランザクションで読み直す。<b>{@code NotificationService} は是正前・是正後の
 * どちらの経路でも必ず通る合流点</b>である（是正前: {@code EscrowNotificationService} 経由／
 * 是正後: {@code NotificationDeliveryRunner#sendOne} 経由）。したがって本 IT は是正前のコードに対しても
 * そのままコンパイル・実行でき、赤→緑を実測で比較できる。<b>赤くなる理由は「通知の失敗が業務トランザクションへ
 * 伝播している」ことそのもの</b>であり、フィクスチャ不足や別の例外ではない。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが起きず
 * リスナーが発火しないまま緑になる（偽の緑）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L7 escrow 通知のトランザクション境界（実DB）")
class EscrowNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EscrowLifecycleService escrowLifecycleService;

    @Autowired
    private EscrowTransactionRepository escrowTransactionRepository;

    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** Stripe 実通信は遮断する（IF 越し）。 */
    @MockitoBean
    private StripePaymentProvider stripePaymentProvider;

    /** 是正前・是正後の双方で必ず通る合流点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("取消通知が失敗しても、escrow の CANCELLED 化はコミットされる")
    void 通知失敗でも与信取消はコミットされる() {
        long payerUserId = 970_000_000L + (System.nanoTime() % 1_000_000L);
        UUID payeeAccountId = insertPayeeAccount(payerUserId);
        // PI 未作成の HELD を使う。Stripe を呼ばずに状態だけ CANCELLED へ落ちる経路であり、
        // 「巻き戻ったのは通知のせいか Stripe のせいか」を混同せずに測れる。
        UUID escrowId = insertEscrow(EscrowStatus.HELD, payerUserId, payeeAccountId, null);

        failAllNotifications();

        assertThatCode(() -> escrowLifecycleService.cancelExpiredHeldOrAuthorized(escrowId))
                .as("通知の失敗が業務メソッドの外へ伝播してはならない")
                .doesNotThrowAnyException();

        EscrowStatus committed = transactionTemplate.execute(
                tx -> escrowTransactionRepository.findById(escrowId).orElseThrow().getStatus());
        assertThat(committed)
                .as("通知が失敗しても与信取消（CANCELLED 化）は巻き戻らない")
                .isEqualTo(EscrowStatus.CANCELLED);

        awaitDeliveryAttempted();
    }

    @Test
    @DisplayName("決済確認依頼通知が失敗しても、HELD 昇格（PI ID と PENDING_CONFIRMATION）はコミットされる")
    void 通知失敗でもHELD昇格はコミットされる() {
        long payerUserId = 980_000_000L + (System.nanoTime() % 1_000_000L);
        UUID payeeAccountId = insertPayeeAccount(payerUserId);
        UUID escrowId = insertEscrow(EscrowStatus.HELD, payerUserId, payeeAccountId, null);

        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(CaptureMethod.class), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo(
                        "pi_l7_boundary", "cs_l7_boundary", "requires_confirmation"));

        failAllNotifications();

        assertThatCode(() -> escrowLifecycleService.promoteHeldEscrow(escrowId))
                .as("札主への決済確認依頼通知の失敗が業務メソッドの外へ伝播してはならない")
                .doesNotThrowAnyException();

        EscrowTransactionEntity committed = transactionTemplate.execute(
                tx -> escrowTransactionRepository.findById(escrowId).orElseThrow());
        assertThat(committed.getStatus())
                .as("通知が失敗しても HELD 昇格は巻き戻らない")
                .isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(committed.getStripePaymentIntentId())
                .as("作成済み PaymentIntent の ID を台帳が失ってはならない（孤児 PI 防止）")
                .isEqualTo("pi_l7_boundary");

        awaitDeliveryAttempted();
    }

    // ---- フィクスチャ / ヘルパ ----

    /**
     * 通知の生成をすべて失敗させる（実 DB 障害と同じ形＝例外の送出）。
     *
     * <p><b>2つのオーバーロードを両方とも失敗させる理由（重要）</b>:
     * {@code createNotification} は 11 引数版と 12 引数版（organizationId あり）があり、
     * 11 引数版は 12 引数版へ委譲する。spy はプロキシであり<b>内部の自己呼び出しは横取りできない</b>ため、
     * 是正前の経路（{@code EscrowNotificationService} が 12 引数版を直接呼ぶ）と
     * 是正後の経路（{@code NotificationDeliveryRunner} が 11 引数版を呼ぶ）では
     * <b>横取りできるオーバーロードが違う</b>。片方だけを stub すると、もう一方の経路では
     * 例外がまったく注入されず「失敗を注入したつもりで実際には何も壊していない」偽の緑になる
     * （実際、本 IT の初版はこれで是正前でも巻き戻りを検出できていなかった）。</p>
     */
    private void failAllNotifications() {
        willThrow(new RuntimeException("模擬通知失敗（#2990 L7 検証用）"))
                .given(notificationService).createNotification(
                        anyLong(), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                        anyString(), any(), any(NotificationScopeType.class), any(), any(), any());
        willThrow(new RuntimeException("模擬通知失敗（#2990 L7 検証用・organizationId 版）"))
                .given(notificationService).createNotification(
                        anyLong(), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                        anyString(), any(), any(NotificationScopeType.class), any(), any(), any(), any());
    }

    /** AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。 */
    private void awaitDeliveryAttempted() {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        anyLong(), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                        anyString(), any(), any(NotificationScopeType.class), any(), any(), any()));
    }

    /** payee は TEAM 口座にする（応じ手への直接通知は境界越えのためスキップされ、札主宛の1件だけになる）。 */
    private UUID insertPayeeAccount(long seed) {
        return transactionTemplate.execute(tx -> connectAccountRepository.save(
                ConnectAccountEntity.builder()
                        .scopeKind(ScopeKind.TEAM)
                        .scopeId(seed + 500L)
                        .stripeAccountId("acct_l7_" + seed)
                        .onboardingStatus(OnboardingStatus.READY)
                        .payoutsEnabled(true)
                        .chargesEnabled(true)
                        .country("JP")
                        .defaultCurrency("JPY")
                        .build()).getId());
    }

    private UUID insertEscrow(EscrowStatus status, long payerUserId, UUID payeeAccountId, String piId) {
        return transactionTemplate.execute(tx -> escrowTransactionRepository.save(
                EscrowTransactionEntity.builder()
                        .sourceKind(EscrowSourceKind.RECRUITMENT)
                        .sourceId(payerUserId)
                        .sourceParticipantId(payerUserId + 1L)
                        .captureMode(EscrowCaptureMode.MANUAL)
                        .payerScopeKind(ScopeKind.USER)
                        .payerScopeId(payerUserId)
                        .payerStripeCustomerId("cus_l7_" + payerUserId)
                        .payeeKind(ScopeKind.TEAM)
                        .payeeConnectAccountId(payeeAccountId)
                        .faceAmount(10_000L)
                        .amount(10_250L)
                        .applicationFeeAmount(500L)
                        .currency("JPY")
                        .status(status)
                        .stripePaymentIntentId(piId)
                        .build()).getId());
    }
}
