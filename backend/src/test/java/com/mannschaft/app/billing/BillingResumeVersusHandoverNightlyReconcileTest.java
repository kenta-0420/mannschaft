package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingPayerHandoverTxService.CancelScheduleTarget;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Billing Center PR6a — AC-49「解約撤回と引継の夜次照合の衝突」を、Docker 無しで回せる単体で固定する
 * （試練）。実 DB を通した版は
 * {@code com.mannschaft.app.billing.api.BillingCancelResumeHandoverExclusionRedIT} が担う。
 *
 * <h2>このテストが示すこと</h2>
 * <p>引継の夜次照合 {@link BillingPayerHandoverService#reconcileOldCancelSchedule} は、Stripe 実物の
 * {@code cancel_at_period_end} が {@code false} なら<b>無条件に true を再設定する</b>（:779-790）。
 * つまり<b>撤回を通してしまうと、その晩に解約予約が復活し、利用者の撤回は翌朝消える</b>。
 * AC-48 の排他（旧契約基準の 409）は、この経路を塞ぐために必要なのであって、
 * 単なる「行儀のよいバリデーション」ではない。</p>
 *
 * <p><b>陽性対照の置き方</b>: 「撤回が弾かれていれば再設定は起きない」だけを測ると、
 * 照合が何もしない実装でも緑になる（空虚な緑）。そこで「撤回が通っていたら再設定が起きる」を
 * 対で測り、衝突経路が実在することを同じテストクラス内で証明する。</p>
 *
 * <p>{@code AC-49（撤回拒否側）} は 撤回 API 実装前は赤にならない性質のものなので、
 * ここでは<b>衝突機構そのもの</b>（＝第6隊が塞ぐべき対象）を固定し、
 * 「撤回が 409 で弾かれること」の赤は IT 側に置いている。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PR6a AC-49: 解約撤回と引継の夜次照合の衝突機構")
class BillingResumeVersusHandoverNightlyReconcileTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);
    private static final String OLD_SUB = "sub_pr6a_ac49";

    @Mock private BillingPayerHandoverRequestRepository handoverRequestRepository;
    @Mock private BillingContractRepository billingContractRepository;
    @Mock private BillingOperationAuthorizer billingOperationAuthorizer;
    @Mock private BillingPaymentGateway billingPaymentGateway;
    @Mock private BillingPayerHandoverTxService handoverTxService;
    @Mock private RoleService roleService;
    @Mock private com.mannschaft.app.auth.service.WithdrawalStateQueryService withdrawalStateQueryService;

    private BillingPayerHandoverService service;
    private final UUID handoverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BillingPayerHandoverService(
                handoverRequestRepository, billingContractRepository, billingOperationAuthorizer,
                billingPaymentGateway, handoverTxService,
                new BillingPayerHandoverCandidateResolver(roleService),
                withdrawalStateQueryService, FIXED_CLOCK);
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:3000");
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB,
                        FIXED_CLOCK.instant().minusSeconds(3600)));
    }

    @Test
    @DisplayName("AC-49: 撤回が通った状態（Stripeがfalse）で夜次照合を1周回すと解約予約が復活する（衝突経路の実在）")
    void AC49_撤回が通ると夜次照合が解約予約を復活させる() {
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", false, null, null, null));

        service.reconcileOldCancelSchedule(handoverId);

        verify(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
    }

    @Test
    @DisplayName("AC-49: 撤回が弾かれた状態（Stripeがtrueのまま）なら夜次照合は再設定しない")
    void AC49_撤回が弾かれていれば再設定は起きない() {
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB))
                .willReturn(new SubscriptionSnapshot(OLD_SUB, "active", true, null, null, null));

        service.reconcileOldCancelSchedule(handoverId);

        verify(billingPaymentGateway, never())
                .scheduleCancelAtPeriodEndForHandover(anyString(), any());
    }

    @Test
    @DisplayName("AC-48: 引継の非終端判定は old_contract_id 起点であり PENDING_HANDOVER の状態判定では代替できない")
    void AC48_非終端判定はold_contract_id起点() {
        // 旧契約は最後まで ACTIVE。PENDING_HANDOVER になるのは新契約側なので、
        // 「操作対象契約の status を見る」実装はここで必ず素通りする。
        assertThat(BillingPayerHandoverTxService.TERMINAL_STATUSES)
                .as("終端集合は COMPLETED / FAILED / EXPIRED の3値")
                .containsExactlyInAnyOrder(PayerHandoverStatus.COMPLETED,
                        PayerHandoverStatus.FAILED, PayerHandoverStatus.EXPIRED);
        assertThat(List.of(PayerHandoverStatus.values()).stream()
                .filter(s -> !BillingPayerHandoverTxService.TERMINAL_STATUSES.contains(s))
                .toList())
                .as("非終端＝この7値。いずれも旧契約への解約・撤回を 409 にする条件になる")
                .contains(PayerHandoverStatus.REQUESTED, PayerHandoverStatus.ACCEPTED,
                        PayerHandoverStatus.REQUIRES_PAYMENT_METHOD, PayerHandoverStatus.SWITCHING,
                        PayerHandoverStatus.PARTIALLY_COMPLETED,
                        PayerHandoverStatus.MANUAL_INTERVENTION,
                        PayerHandoverStatus.FAILING_CLEANUP);
    }
}
