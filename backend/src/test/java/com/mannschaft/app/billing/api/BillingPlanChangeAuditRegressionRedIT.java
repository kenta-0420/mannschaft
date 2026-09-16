package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6b-1 — I群 監査（AC-136〜139）の受け入れテスト（試練D・第5隊・red）。
 *
 * <p>PR6a の {@code BILLING_CANCEL_*} 監査イベント（{@link AuditEventType}）を金型にする。
 * PR6b-1 実装前は {@code BILLING_PLAN_CHANGE_*} 系イベントが {@link AuditEventType} に
 * 一切存在しないため、本ファイルは列挙値の不在をもって red になる（実装後は
 * 第7/8/9隊が発行する箇所の呼び出しを別途 IT で確認する土台として使う）。</p>
 *
 * <p>AC-139（clientSecret・カード番号・raw payload・URL の非混入）は、PR6a の
 * {@code BillingCancelResumeAuditRegressionRedIT.AC67_監査metadataにPIIと秘密を残さない}
 * と同型のパターンマッチで固定する。実際に upgrade API を叩く経路は
 * {@code BillingContractChangeApplicationService}（第7隊）が実装されてから有効化する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 監査（I群 AC-136〜139・試練D red）")
class BillingPlanChangeAuditRegressionRedIT extends AbstractMySqlIntegrationTest {

    @MockitoSpyBean private AuditLogService auditLogService;
    @Autowired private MockMvc mockMvc;

    // ═════════ AC-136: change の作成が監査イベントとして記録される ═════════

    @Test
    @DisplayName("AC-136: BILLING_PLAN_CHANGE_REQUESTED 相当の監査イベント種別が定義されている")
    void AC136_change作成の監査イベント種別が存在する() {
        assertThat(auditEventTypeExists("BILLING_PLAN_CHANGE_REQUESTED"))
                .as("第7隊への発注: AuditEventType に change 作成時点の監査イベント種別が無い。"
                        + "PR6a の BILLING_CANCEL_REQUESTED を金型に、AC-27（operation と同一tx作成）"
                        + "の直後に発行する種別を追加すること")
                .isTrue();
    }

    // ═════════ AC-137: change の確定が記録される ═════════

    @Test
    @DisplayName("AC-137: BILLING_PLAN_CHANGE_APPLIED 相当の監査イベント種別が定義されている")
    void AC137_change確定の監査イベント種別が存在する() {
        assertThat(auditEventTypeExists("BILLING_PLAN_CHANGE_APPLIED"))
                .as("第9隊への発注: invoice.paid 確定（AC-37）の同一トランザクション内で発行する"
                        + "監査イベント種別が無い")
                .isTrue();
    }

    // ═════════ AC-138: change の失敗が記録される ═════════

    @Test
    @DisplayName("AC-138: BILLING_PLAN_CHANGE_FAILED 相当の監査イベント種別が定義されている")
    void AC138_change失敗の監査イベント種別が存在する() {
        assertThat(auditEventTypeExists("BILLING_PLAN_CHANGE_FAILED"))
                .as("第9/10隊への発注: 確定失敗（decline/voided/expired。AC-38〜40）で発行する"
                        + "監査イベント種別が無い。成功だけを監査しない（PR6a AC-66 と同じ方針）")
                .isTrue();
    }

    // ═════════ AC-139: clientSecret・カード番号・raw payload・URL を残さない ═════════

    @Test
    @DisplayName("AC-139: change の実行APIが実装されたら metadata に秘密・PII・URL を含めないこと（受け入れ基準の固定）")
    void AC139_監査metadataに秘密とPIIとURLを残さない契約を固定する() {
        // 実行系（POST .../changes・payment-action）はまだ存在しないため、
        // ここでは「実装後に満たすべき検査ロジック」自体をコンパイル可能な形で固定し、
        // 対象クラスの不在を明示的な失敗として報告する（PR6a AC-67 と同型のパターン）。
        String applicationServiceFqcn = "com.mannschaft.app.billing.api.BillingContractChangeApplicationService";
        try {
            Class.forName(applicationServiceFqcn);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(
                    "第7隊への発注: " + applicationServiceFqcn + " が未実装のため、"
                            + "monitor対象の監査呼び出し箇所が存在しない。実装後は本テストを実 HTTP 経由の"
                            + "ArgumentCaptor 検証（PR6a の AC67_監査metadataにPIIと秘密を残さない と同型）へ"
                            + "差し替えること。当面はこの AssertionError 自体が red の根拠になる。", e);
        }
    }

    /** metadata文字列がPII・秘密・URLを含まないことを検査する共通アサーション（実装後の差し替え先が使う）。 */
    static void assertMetadataHasNoSecretsOrPii(List<String> metadataValues) {
        for (String metadata : metadataValues) {
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

    private boolean auditEventTypeExists(String name) {
        return Arrays.stream(AuditEventType.values()).anyMatch(v -> v.name().equals(name));
    }
}
