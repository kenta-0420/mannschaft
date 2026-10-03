package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — I群 監査（AC-136〜139）の受け入れテスト（試練D・第5隊 red → 第13隊 green化）。
 *
 * <p>PR6a の {@code BILLING_CANCEL_*} 監査イベント（{@link AuditEventType}）を金型に、
 * 第13隊が {@code BILLING_PLAN_CHANGE_*} 系イベントを追加し、発行元
 * （{@code BillingPlanChangeService}）を実装した。AC-136（作成）と AC-138 の
 * 「Stripe 呼び出し自体の同期失敗」経路は本ファイルが HTTP 経由（{@code POST .../changes}）で固定する。</p>
 *
 * <p>AC-137（{@code invoice.paid} 確定）と AC-138 の webhook 起点（decline/voided/expired）は
 * {@link com.mannschaft.app.billing.BillingPlanChangeConfirmationService} が発行する。
 * webhook 署名検証を要する HTTP 経由の再現は試練層として過大なため（PR6a
 * {@code AC73_所有判定は逆引きでヒットしなければフォールバックする} と同じ判断）、
 * Docker 不要の純 UT {@code com.mannschaft.app.billing.BillingPlanChangeConfirmationServiceTest}
 * で確定・冪等・metadata の3点を固定する。</p>
 *
 * <p>AC-139（clientSecret・カード番号・raw payload・URL の非混入）は、PR6a の
 * {@code BillingCancelResumeAuditRegressionRedIT.AC67_監査metadataにPIIと秘密を残さない}
 * と同型のパターンマッチで固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 監査（I群 AC-136〜139・第13隊 green）")
class BillingPlanChangeAuditRegressionRedIT extends AbstractBillingPlanChangeApiIT {

    @MockitoSpyBean private AuditLogService auditLogService;

    @BeforeEach
    void setUpFixture() {
        seedUpgradableContract("audit");
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ═════════ AC-136: change の作成が監査イベントとして記録される ═════════

    @Test
    @DisplayName("AC-136: upgrade実行の受理でBILLING_PLAN_CHANGE_REQUESTEDが記録される")
    void AC136_change作成は監査イベントとして記録される() throws Exception {
        stubStripeApply("in_pr6b1_audit_sync", "paid", false);
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.atLeastOnce())
                .record(eq(AuditEventType.BILLING_PLAN_CHANGE_REQUESTED.name()),
                        eq(userId), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ═════════ AC-138: Stripe 呼び出し自体の同期失敗も記録される ═════════

    @Test
    @DisplayName("AC-138: Stripe呼び出し自体の失敗もBILLING_PLAN_CHANGE_FAILEDとして記録される")
    void AC138_Stripe呼び出し失敗も監査イベントとして記録される() throws Exception {
        willThrow(new IllegalStateException("stripe down"))
                .given(planChangeGateway).applyPlanChange(any());
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isBadGateway());

        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.atLeastOnce())
                .record(eq(AuditEventType.BILLING_PLAN_CHANGE_FAILED.name()),
                        eq(userId), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ═════════ AC-139: clientSecret・カード番号・raw payload・URL を残さない ═════════

    @Test
    @DisplayName("AC-139: 監査metadataにカード番号・raw payload・client secret・URLを残さない")
    void AC139_監査metadataに秘密とPIIとURLを残さない() throws Exception {
        stubStripeApply("in_pr6b1_audit_pii", "paid", false);
        UUID previewId = createPreviewId();

        change(userId, contractId, previewId, contractVersion(), newKey())
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType(MediaType.APPLICATION_JSON));

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.atLeastOnce())
                .record(eq(AuditEventType.BILLING_PLAN_CHANGE_REQUESTED.name()),
                        eq(userId), any(), any(), any(), any(), any(), any(), metadataCaptor.capture());

        for (String metadata : metadataCaptor.getAllValues()) {
            if (metadata == null) {
                continue;
            }
            assertThat(metadata).as("client_secretを含めない").doesNotContainIgnoringCase("client_secret");
            assertThat(metadata).as("payment_intentのraw payloadを含めない")
                    .doesNotContainIgnoringCase("\"object\":\"payment_intent\"");
            assertThat(metadata).as("card番号らしき16桁連続数字を含めない").doesNotMatch(".*\\d{16}.*");
            assertThat(metadata).as("http(s) URLを含めない（Stripeダッシュボードリンク等の混入防止）")
                    .doesNotContain("http://").doesNotContain("https://");
        }
    }
}
