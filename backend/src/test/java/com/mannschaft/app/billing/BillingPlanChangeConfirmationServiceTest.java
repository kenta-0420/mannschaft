package com.mannschaft.app.billing;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Billing Center PR6b-1 — I群 監査（AC-136〜139・第13隊）の受け入れテスト。
 *
 * <p>{@link BillingPlanChangeAuditRegressionRedIT}（試練D・第5隊）が固定した「監査イベント種別が
 * {@link AuditEventType} に存在すること」までの red を、確定（{@link BillingPlanChangeConfirmationService})
 * が実際に発行することの検証まで green 化する。Docker が無いローカルでも走る純 UT
 * （実 DB を使わず全依存を Mockito で差し替える）。</p>
 *
 * <p>metadata の非混入契約（AC-139）は PR6a
 * {@code BillingCancelResumeAuditRegressionRedIT#AC67_監査metadataにPIIと秘密を残さない} と同型の
 * パターンマッチで検査する。</p>
 */
class BillingPlanChangeConfirmationServiceTest {

    private static final long ACTOR_ID = 42L;
    private static final long FROM_AMOUNT = 1_200L;
    private static final long TO_AMOUNT = 3_300L;

    private BillingContractChangeRepository changeRepository;
    private BillingContractRepository contractRepository;
    private BillingContractOperationSagaService sagaService;
    private BillingPaymentGateway billingPaymentGateway;
    private BillingInvoiceJpaRepository invoiceRepository;
    private StripeBillingPayloadParser payloadParser;
    private AuditLogService auditLogService;
    private BillingPlanChangeConfirmationService service;

    @BeforeEach
    void setUp() {
        changeRepository = mock(BillingContractChangeRepository.class);
        contractRepository = mock(BillingContractRepository.class);
        sagaService = mock(BillingContractOperationSagaService.class);
        billingPaymentGateway = mock(BillingPaymentGateway.class);
        invoiceRepository = mock(BillingInvoiceJpaRepository.class);
        payloadParser = mock(StripeBillingPayloadParser.class);
        auditLogService = mock(AuditLogService.class);
        service = new BillingPlanChangeConfirmationService(
                changeRepository, contractRepository, sagaService,
                billingPaymentGateway, invoiceRepository, payloadParser, auditLogService);

        // applyAndFinalize/failAndRelease は PR6a 資産（Saga）そのもの。ここでは
        // 「reflection を1回実行して結果を返す」ことだけを模す（tx 境界自体は別番人の担当）。
        given(sagaService.applyAndFinalize(any(UUID.class), any())).willAnswer(invocation -> {
            java.util.function.Supplier<?> reflection = invocation.getArgument(1);
            return reflection.get();
        });
    }

    // ═════════ AC-137: change の確定（invoice.paid）が監査イベントとして記録される ═════════

    @Test
    void AC137_paid確定はBILLING_PLAN_CHANGE_APPLIEDを1回だけ記録する() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.PENDING_PAYMENT);
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(change.getOperationId()))
                .willReturn(Optional.of(change));
        given(contractRepository.findByIdAndDeletedAtIsNull(contractId))
                .willReturn(Optional.of(contractFixture(contractId)));

        service.confirmPaid(change, "in_upgrade_paid");

        verify(auditLogService, times(1)).record(
                eq(AuditEventType.BILLING_PLAN_CHANGE_APPLIED.name()), eq(ACTOR_ID), any(),
                any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void AC137_既に確定済みの再送は監査しない冪等() {
        UUID contractId = UUID.randomUUID();
        // 既に APPLIED（IN_FLIGHT でない）＝ 二度目の webhook 再送を模す（AC-86）。
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.APPLIED);

        service.confirmPaid(change, "in_upgrade_paid");

        verify(auditLogService, never()).record(
                eq(AuditEventType.BILLING_PLAN_CHANGE_APPLIED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ═════════ AC-138: change の失敗（decline/voided/expired）が監査イベントとして記録される ═════════

    @Test
    void AC138_失敗確定はBILLING_PLAN_CHANGE_FAILEDをerrorCode付きで記録する() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.REQUIRES_ACTION);
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(change.getOperationId()))
                .willReturn(Optional.of(change));
        given(contractRepository.findByIdAndDeletedAtIsNull(contractId))
                .willReturn(Optional.of(contractFixture(contractId)));

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        service.confirmFailed(change, "CARD_DECLINED");

        verify(auditLogService, times(1)).record(
                eq(AuditEventType.BILLING_PLAN_CHANGE_FAILED.name()), eq(ACTOR_ID), any(),
                any(), any(), any(), any(), any(), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"errorCode\":\"CARD_DECLINED\"");
    }

    @Test
    void AC138_既に確定済みの失敗再送は監査しない冪等() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity change = changeFixture(
                contractId, BillingContractChangeStatus.FAILED);

        service.confirmFailed(change, "CARD_DECLINED");

        verify(auditLogService, never()).record(
                eq(AuditEventType.BILLING_PLAN_CHANGE_FAILED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ═════════ AC-139: metadata に秘密・PII・URL を残さない ═════════

    @Test
    void AC139_監査metadataに秘密とPIIとURLを残さない() {
        UUID contractId = UUID.randomUUID();
        BillingContractChangeEntity paidChange = changeFixture(
                contractId, BillingContractChangeStatus.PENDING_PAYMENT);
        given(changeRepository.findByOperationIdAndDeletedAtIsNull(paidChange.getOperationId()))
                .willReturn(Optional.of(paidChange));
        given(contractRepository.findByIdAndDeletedAtIsNull(contractId))
                .willReturn(Optional.of(contractFixture(contractId)));

        service.confirmPaid(paidChange, "in_upgrade_paid_" + "pi_1234567890123456");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(AuditEventType.BILLING_PLAN_CHANGE_APPLIED.name()),
                any(), any(), any(), any(), any(), any(), any(), metadataCaptor.capture());

        String metadata = metadataCaptor.getValue();
        assertThat(metadata).as("client_secretを含めない").doesNotContainIgnoringCase("client_secret");
        assertThat(metadata).as("payment_intentのraw payloadを含めない")
                .doesNotContainIgnoringCase("\"object\":\"payment_intent\"");
        assertThat(metadata).as("card番号らしき16桁連続数字を含めない").doesNotMatch(".*\\d{16}.*");
        assertThat(metadata).as("http(s) URLを含めない（Stripeダッシュボードリンク等の混入防止）")
                .doesNotContain("http://").doesNotContain("https://");
    }

    // ═════════ フィクスチャ ═════════

    private BillingContractChangeEntity changeFixture(UUID contractId, BillingContractChangeStatus status) {
        Instant now = Instant.now();
        return BillingContractChangeEntity.builder()
                .operationId(UUID.randomUUID())
                .contractId(contractId)
                .billingCustomerId(UUID.randomUUID())
                .kind(BillingContractChangeKind.UPGRADE)
                .status(status)
                .fromPlanKey("BASIC")
                .toPlanKey("FULL")
                .fromPriceBandVersionId(UUID.randomUUID())
                .toPriceBandVersionId(UUID.randomUUID())
                .fromAmountIncludingTax(FROM_AMOUNT)
                .toAmountIncludingTax(TO_AMOUNT)
                .stripeSubscriptionRef("sub_pr6b1_test")
                .effectiveAt(now)
                .idempotencyKey(UUID.randomUUID().toString())
                .requestHash("0".repeat(64))
                .version(0L)
                .createdBy(ACTOR_ID)
                .build();
    }

    private BillingContractEntity contractFixture(UUID contractId) {
        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(ACTOR_ID)
                .contractKind(ContractKind.PLAN).planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot((int) FROM_AMOUNT)
                .createdBy(ACTOR_ID).payerUserId(ACTOR_ID)
                .version(0L)
                .build();
        contract.setId(contractId);
        return contract;
    }
}
