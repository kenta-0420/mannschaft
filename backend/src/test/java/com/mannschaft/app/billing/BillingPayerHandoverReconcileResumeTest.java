package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingPayerHandoverService.ResumeTarget;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.CancelScheduleTarget;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.ResumeContext;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.common.BusinessException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.willThrow;

/**
 * 柱③-B PR-4（CMP-260901-1538）: {@code cancel_at_period_end} の夜次照合（AC-34）と
 * {@code MANUAL_INTERVENTION} からの {@code RESUME}（AC-37）の単体テスト（試練先行）。
 *
 * <p>設計書 §3.6.1(a)・§3.6.2。いずれも<b>DB の記録を信用せず Stripe 実物と突合する</b>ことが要点であり、
 * 「DB が NULL だから未設定」と決めつけて再設定する実装は AC-34 を満たさない
 * （設定 API は成功していて DB 書き込みだけが落ちたケースを二重に叩くことになる）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BillingPayerHandoverService: 夜次照合と MANUAL_INTERVENTION の RESUME")
class BillingPayerHandoverReconcileResumeTest {

    private static final Instant NOW = Instant.parse("2026-09-09T02:40:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR = 8L;
    private static final String OLD_SUB = "sub_old";
    private static final String NEW_SUB = "sub_new";

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
    }

    private SubscriptionSnapshot snapshot(boolean cancelAtPeriodEnd) {
        return new SubscriptionSnapshot(OLD_SUB, "active", cancelAtPeriodEnd, null, null, null);
    }

    // ============================================================
    // AC-34: cancel_at_period_end の夜次照合
    // ============================================================

    @Test
    @DisplayName("AC-34: Stripe 側が既に true なら再設定せず old_cancel_scheduled_at だけ埋める（冪等）")
    void reconcile_stripeAlreadyTrue_onlyRepairsDb() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB, NOW.minusSeconds(3600)));
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB)).willReturn(snapshot(true));

        service.reconcileOldCancelSchedule(handoverId);

        verify(billingPaymentGateway, never()).scheduleCancelAtPeriodEndForHandover(anyString(), any());
        verify(handoverTxService).persistOldCancelScheduledAt(eq(handoverId), any(Instant.class));
    }

    @Test
    @DisplayName("AC-34: Stripe 側が false なら設定 API を再実行してから old_cancel_scheduled_at を埋める")
    void reconcile_stripeFalse_reschedules() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB, NOW.minusSeconds(3600)));
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB)).willReturn(snapshot(false));

        service.reconcileOldCancelSchedule(handoverId);

        verify(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
        verify(handoverTxService).persistOldCancelScheduledAt(eq(handoverId), any(Instant.class));
    }

    @Test
    @DisplayName("AC-34: Stripe を引けなかった場合は DB を書き換えない（曖昧なまま整合済みと記録しない）")
    void reconcile_snapshotUnavailable_doesNotPersist() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB, NOW.minusSeconds(3600)));
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB)).willReturn(null);

        service.reconcileOldCancelSchedule(handoverId);

        verify(handoverTxService, never()).persistOldCancelScheduledAt(any(), any());
    }

    @Test
    @DisplayName("対象外（既に解決済み・終端化済み）なら Stripe を一切叩かない")
    void reconcile_noTarget_noStripeCall() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId)).willReturn(null);

        service.reconcileOldCancelSchedule(handoverId);

        verify(billingPaymentGateway, never()).retrieveSubscription(anyString());
        verify(handoverTxService, never()).persistOldCancelScheduledAt(any(), any());
    }

    @Test
    @DisplayName("P1-3: 設定 API が失敗し承諾から猶予（3日）を過ぎていれば MANUAL_INTERVENTION へ倒す"
            + "（恒久失敗を無限再試行にしない）")
    void reconcile_permanentFailure_escalatesToManualIntervention() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB,
                        NOW.minus(java.time.Duration.ofDays(4))));
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB)).willReturn(snapshot(false));
        willThrow(new IllegalStateException("stripe 4xx"))
                .given(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);

        service.reconcileOldCancelSchedule(handoverId);

        verify(handoverTxService).markManualIntervention(handoverId);
        // 曖昧なまま「整合済み」と記録してはならない（記録すると以後の抽出から外れる）。
        verify(handoverTxService, never()).persistOldCancelScheduledAt(any(), any());
    }

    @Test
    @DisplayName("P1-3: 猶予内の失敗は一時的とみなして例外を上げ、状態を変えない（自動回復の余地を残す）")
    void reconcile_transientFailure_doesNotEscalate() {
        given(handoverTxService.loadCancelScheduleTarget(handoverId))
                .willReturn(new CancelScheduleTarget(handoverId, OLD_SUB, NOW.minusSeconds(3600)));
        given(billingPaymentGateway.retrieveSubscription(OLD_SUB)).willReturn(snapshot(false));
        willThrow(new IllegalStateException("timeout"))
                .given(billingPaymentGateway).scheduleCancelAtPeriodEndForHandover(OLD_SUB, handoverId);

        assertThatThrownBy(() -> service.reconcileOldCancelSchedule(handoverId))
                .isInstanceOf(IllegalStateException.class);

        verify(handoverTxService, never()).markManualIntervention(any());
        verify(handoverTxService, never()).persistOldCancelScheduledAt(any(), any());
    }

    // ============================================================
    // P1-6 / AC-20: SWITCHING 滞留と追加認証の期限処理
    // ============================================================

    @Test
    @DisplayName("AC-20: 承諾から24時間を過ぎても pending_setup_intent 未解決なら"
            + "【旧期末を待たずに】FAILED 確定し、新サブスク取消と旧の差し戻しを行う")
    void stalledSwitching_unresolvedAuth_failsBeforePeriodEnd() {
        given(handoverTxService.loadStalledSwitchingTarget(handoverId))
                .willReturn(new BillingPayerHandoverTxService.StalledSwitchingTarget(
                        handoverId, OLD_SUB, NEW_SUB, NOW.minus(java.time.Duration.ofDays(2)), 8L));
        given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", false, null, null, "seti_1"));
        given(handoverTxService.failStalledSwitchingAndRenotify(handoverId)).willReturn(true);

        service.reconcileStalledSwitching(handoverId);

        verify(billingPaymentGateway).cancelHandoverNewSubscription(NEW_SUB, handoverId);
        // ★旧期末より前なので差し戻しが有効に効く（期末到達後だと復旧できない）。
        verify(billingPaymentGateway).revertCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
        verify(handoverTxService).failStalledSwitchingAndRenotify(handoverId);
    }

    @Test
    @DisplayName("P1-6: 認証が完了していれば滞留していても終端化しない（DB の滞留だけで判断しない）")
    void stalledSwitching_authResolved_doesNotFail() {
        given(handoverTxService.loadStalledSwitchingTarget(handoverId))
                .willReturn(new BillingPayerHandoverTxService.StalledSwitchingTarget(
                        handoverId, OLD_SUB, NEW_SUB, NOW.minus(java.time.Duration.ofDays(2)), 8L));
        given(billingPaymentGateway.retrieveSubscription(NEW_SUB))
                .willReturn(new SubscriptionSnapshot(NEW_SUB, "trialing", true, null, null, null));

        service.reconcileStalledSwitching(handoverId);

        verify(handoverTxService, never()).failStalledSwitchingAndRenotify(any());
        verify(billingPaymentGateway, never()).cancelHandoverNewSubscription(anyString(), any());
    }

    @Test
    @DisplayName("P1-6: 新サブスクを Stripe から引けない間は状態を変えない（曖昧なまま終端化しない）")
    void stalledSwitching_snapshotUnavailable_doesNothing() {
        given(handoverTxService.loadStalledSwitchingTarget(handoverId))
                .willReturn(new BillingPayerHandoverTxService.StalledSwitchingTarget(
                        handoverId, OLD_SUB, NEW_SUB, NOW.minus(java.time.Duration.ofDays(2)), 8L));
        given(billingPaymentGateway.retrieveSubscription(NEW_SUB)).willReturn(null);

        service.reconcileStalledSwitching(handoverId);

        verify(handoverTxService, never()).failStalledSwitchingAndRenotify(any());
        verify(billingPaymentGateway, never()).cancelHandoverNewSubscription(anyString(), any());
    }

    // ============================================================
    // AC-37: RESUME
    // ============================================================

    @Test
    @DisplayName("AC-37: RESUME→SWITCHING は状態を SWITCHING に戻すだけで Stripe を触らない")
    void resume_toSwitching_localOnly() {
        given(handoverTxService.loadResumeContext(
                EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR))
                .willReturn(new ResumeContext(handoverId, OLD_SUB, NEW_SUB));

        service.resumeManualIntervention(EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR,
                ResumeTarget.SWITCHING, false);

        verify(handoverTxService).resumeToSwitching(handoverId);
        verify(billingPaymentGateway, never()).revertCancelAtPeriodEndForHandover(anyString(), any());
        verify(handoverTxService, never()).markFailedAndClearCancelSchedule(any());
    }

    @Test
    @DisplayName("AC-37/AC-32: RESUME→FAILED（差し戻しあり）は旧サブスクを継続へ戻し old_cancel_scheduled_at を NULL クリアする")
    void resume_toFailed_withRevert() {
        given(handoverTxService.loadResumeContext(
                EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR))
                .willReturn(new ResumeContext(handoverId, OLD_SUB, NEW_SUB));

        service.resumeManualIntervention(EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR,
                ResumeTarget.FAILED, true);

        verify(billingPaymentGateway).cancelHandoverNewSubscription(NEW_SUB, handoverId);
        verify(billingPaymentGateway).revertCancelAtPeriodEndForHandover(OLD_SUB, handoverId);
        verify(handoverTxService).markFailedAndClearCancelSchedule(handoverId);
    }

    @Test
    @DisplayName("AC-37: RESUME→FAILED（差し戻しなし）は運用者の判断どおり旧サブスクの予約に触れない")
    void resume_toFailed_withoutRevert() {
        given(handoverTxService.loadResumeContext(
                EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR))
                .willReturn(new ResumeContext(handoverId, OLD_SUB, NEW_SUB));

        service.resumeManualIntervention(EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR,
                ResumeTarget.FAILED, false);

        verify(billingPaymentGateway, never()).revertCancelAtPeriodEndForHandover(anyString(), any());
        verify(handoverTxService).markFailedAndClearCancelSchedule(handoverId);
    }

    @Test
    @DisplayName("AC-37: MANUAL_INTERVENTION 以外からの RESUME は拒否される（Tx 層の検証が伝播する）")
    void resume_rejectedWhenNotManualIntervention() {
        given(handoverTxService.loadResumeContext(
                EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR))
                .willThrow(new BusinessException(EntitlementErrorCode.HANDOVER_NOT_RESUMABLE));

        assertThatThrownBy(() -> service.resumeManualIntervention(
                EntitlementScopeKind.TEAM, TEAM_ID, handoverId, OPERATOR, ResumeTarget.SWITCHING, false))
                .isInstanceOf(BusinessException.class);

        verify(handoverTxService, never()).resumeToSwitching(any());
    }
}
