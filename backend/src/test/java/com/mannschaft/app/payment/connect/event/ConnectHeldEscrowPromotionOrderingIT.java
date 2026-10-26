package com.mannschaft.app.payment.connect.event;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectAccountService;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Connect 受取口座の払出解禁 → HELD escrow 昇格の<b>因果順序</b>の実DB検証（Issue #2990 L3）。
 *
 * <h2>このITが証明するメカニズム</h2>
 * <p>{@code EscrowLifecycleService#promoteHeldEscrow} は {@code @Transactional(REQUIRES_NEW)} であり、
 * その内部で payee 口座を<b>読み直して</b> {@code payouts_enabled} を検証する。したがって
 * 「鏡像更新（payouts_enabled=false→true）を書いた業務TXが commit しているか」で結果が変わる:</p>
 *
 * <ul>
 *   <li><b>是正前</b>（業務TX の内側から直接呼ぶ）: {@code REQUIRES_NEW} は別コネクション・別TXであり、
 *       READ COMMITTED の下では未 commit の更新を読めない。読み直した {@code payouts_enabled} は
 *       常に旧値 {@code false} なので、{@code promoteHeldEscrow} は
 *       「HELD 昇格スキップ: payee 口座が解決不能/未 READY」で {@code false} を返し、
 *       escrow は <b>HELD のまま</b>・PaymentIntent も作られず・札主への決済確認依頼通知も飛ばない。</li>
 *   <li><b>是正後</b>（AFTER_COMMIT リスナーから呼ぶ）: 読み直しは commit 済みの新値 {@code true} を見るので
 *       昇格が成立し、escrow は {@code PENDING_CONFIRMATION} へ遷移する。</li>
 * </ul>
 *
 * <p>よって本ITの assert（{@code PENDING_CONFIRMATION} になっていること）は、
 * 是正前のコードでは<b>「HELD のまま」で必ず落ちる</b>。落ちる理由は可視性フィルタでも
 * モックの取り違えでもなく、検証したい因果順序そのものである。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>昇格は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むと commit が
 * 起きずリスナーが 1 度も走らないまま「昇格しないことを確認できた」ことになる（偽の緑）。
 * よって本クラスはトランザクションを張らず、フィクスチャ投入は {@link TransactionTemplate} で
 * 明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F22.1/#2990 L3 Connect 払出解禁 → HELD escrow 昇格の因果順序（AFTER_COMMIT）")
class ConnectHeldEscrowPromotionOrderingIT extends AbstractMySqlIntegrationTest {

    @Autowired private ConnectAccountService connectAccountService;
    @Autowired private ConnectAccountRepository connectAccountRepository;
    @Autowired private EscrowTransactionRepository escrowTransactionRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /** Stripe 実通信は遮断する（PI 作成は成功を返す）。 */
    @MockitoBean private StripePaymentProvider stripePaymentProvider;

    /**
     * 通知の実配送は本ITの関心外。#2990 L7 で配送が AFTER_COMMIT リスナー経由になったため、
     * 昇格の後段が到達したことは配送 Runner が呼ばれたかどうかで測る。
     */
    @MockitoBean private NotificationDeliveryRunner notificationDeliveryRunner;

    @Test
    @DisplayName("鏡像更新の commit 後に昇格が走るので、REQUIRES_NEW の読み直しが新値を見て PENDING_CONFIRMATION へ遷移する")
    void promotionSeesCommittedPayoutsEnabled() {
        String stripeAccountId = "acct_ordering_" + UUID.randomUUID().toString().substring(0, 8);

        given(stripePaymentProvider.createDestinationPaymentIntent(
                anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(CaptureMethod.class), anyString()))
                .willReturn(new StripePaymentProvider.PaymentIntentInfo(
                        "pi_ordering_test", "pi_ordering_test_secret", "requires_confirmation"));

        // ── フィクスチャ: payouts_enabled=false の口座と、それを payee とする HELD escrow を明示 commit ──
        UUID escrowId = transactionTemplate.execute(status -> {
            ConnectAccountEntity account = ConnectAccountEntity.builder()
                    .scopeKind(ScopeKind.TEAM)
                    .scopeId(4242L)
                    .stripeAccountId(stripeAccountId)
                    .onboardingStatus(OnboardingStatus.ONBOARDING)
                    .chargesEnabled(false)
                    .payoutsEnabled(false)
                    .build();
            ConnectAccountEntity savedAccount = connectAccountRepository.save(account);

            EscrowTransactionEntity escrow = EscrowTransactionEntity.builder()
                    .sourceKind(EscrowSourceKind.RECRUITMENT)
                    .sourceId(System.nanoTime() % 1_000_000_000L)
                    .sourceParticipantId(System.nanoTime() % 1_000_000_000L)
                    .captureMode(EscrowCaptureMode.MANUAL)
                    .payerScopeKind(ScopeKind.USER)
                    .payerScopeId(9L)
                    .payerStripeCustomerId("cus_ordering_test")
                    .payeeKind(ScopeKind.TEAM)
                    .payeeConnectAccountId(savedAccount.getId())
                    .faceAmount(10_000L)
                    .amount(10_250L)
                    .applicationFeeAmount(500L)
                    .currency("JPY")
                    .status(EscrowStatus.HELD)
                    .build();
            return escrowTransactionRepository.save(escrow).getId();
        });

        // 事前条件: HELD かつ PI 未作成であること（フィクスチャが意図どおり入っている裏取り）
        EscrowTransactionEntity before = escrowTransactionRepository.findById(escrowId).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(before.getStripePaymentIntentId()).isNull();

        // ── 実行: account.updated 相当（payouts_enabled false→true）──
        // 本メソッドはテスト側でトランザクションを張っていないため、
        // applyAccountUpdated 自身の @Transactional が commit し、その AFTER_COMMIT で昇格が走る。
        connectAccountService.applyAccountUpdated(stripeAccountId, true, true, List.of());

        // ── 検証: 昇格が成立している（＝REQUIRES_NEW の読み直しが commit 済みの新値を見た）──
        EscrowTransactionEntity after = escrowTransactionRepository.findById(escrowId).orElseThrow();
        assertThat(after.getStatus())
                .as("是正前は業務TX 未 commit のため REQUIRES_NEW が payouts_enabled=false を読み、"
                        + "HELD のまま据え置かれる。AFTER_COMMIT へ移して初めて昇格が成立する。")
                .isEqualTo(EscrowStatus.PENDING_CONFIRMATION);
        assertThat(after.getStripePaymentIntentId()).isEqualTo("pi_ordering_test");

        // 札主への決済確認依頼通知が発火していること（昇格の後段が到達していることの裏取り）。
        // #2990 L7 以降、配送は AFTER_COMMIT + @Async のリスナー経由なので非同期に待つ。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ArgumentCaptor<NotificationDeliveryRequest> captor =
                    ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
            verify(notificationDeliveryRunner).sendOne(captor.capture());
            assertThat(captor.getValue().notificationType()).isEqualTo("ESCROW_PAYMENT_REQUIRED");
        });
    }
}
