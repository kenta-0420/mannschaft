package com.mannschaft.app.auth.mfa;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.AuditEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: 2FA の監査とセキュリティ通知メール（AC-12・AC-35・AC-37）。
 *
 * <p>有効化・無効化・バックアップコード使用は本人が気づくべき事象である。監査（{@code audit_logs}）に残し、
 * 本人の登録アドレスへ {@code MFA_SECURITY_NOTICE} を enqueue する（{@code email_outbox} 行で観測）。
 * 通知本文に平文のコードを載せてはならない。</p>
 *
 * <p>無効化時の監査・通知は {@link MfaDisableIT}（AC-31）、回復時は {@link MfaRecoveryFlowIT}（AC-52）で検証する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA 監査・セキュリティ通知（AC-12・AC-35・AC-37）")
class MfaSecurityNoticeIT extends MfaDisableRecoveryItSupport {

    // ═════════════════════════════════════════════════════════════
    // AC-12 バックアップコード使用後
    // ═════════════════════════════════════════════════════════════

    /** AC-12: バックアップコードでのログイン成功応答に残数（7）が載る。 */
    @Test
    @DisplayName("AC-12 バックアップコード使用の応答にbackupCodesRemainingが載る")
    void バックアップコード使用の応答に残数が載る() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = validate(loginForMfaSession(u), "BACKUP_CODE", u.backupCodes().get(0));

        assertThat(r.getResponse().getStatus()).as("AC-12 前提: %s", body(r)).isEqualTo(200);
        assertThat(json(r).path("data").path("backupCodesRemaining").asInt(-1)).as("AC-12 残数").isEqualTo(7);
    }

    /** AC-12/AC-35: 監査 MFA_BACKUP_CODE_USED が user_id 付きで残る。 */
    @Test
    @DisplayName("AC-12 AC-35 バックアップコード使用で監査MFA_BACKUP_CODE_USEDが残る")
    void バックアップコード使用で監査が残る() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = validate(loginForMfaSession(u), "BACKUP_CODE", u.backupCodes().get(0));

        assertThat(r.getResponse().getStatus()).as("AC-12 前提: %s", body(r)).isEqualTo(200);
        awaitAudit(u.userId(), "MFA_BACKUP_CODE_USED", 1);
    }

    /** AC-12/AC-37: 本人宛の MFA_SECURITY_NOTICE が enqueue され、本文変数に平文コードを含まない。 */
    @Test
    @DisplayName("AC-12 AC-37 バックアップコード使用で本人宛通知メールが出て平文コードを含まない")
    void バックアップコード使用で通知メールが出る() throws Exception {
        MfaUser u = createTotpUser();
        String used = u.backupCodes().get(0);

        MvcResult r = validate(loginForMfaSession(u), "BACKUP_CODE", used);

        assertThat(r.getResponse().getStatus()).as("AC-12 前提: %s", body(r)).isEqualTo(200);
        List<Map<String, String>> payloads = awaitOutboxPayloads(u.userId(), "MFA_SECURITY_NOTICE", 1);
        assertThat(outboxRecipients(u.userId(), "MFA_SECURITY_NOTICE")).as("AC-37 宛先は本人").containsOnly(u.email());
        payloads.forEach(p -> p.values().forEach(v ->
                assertThat(v == null ? "" : v).as("AC-12 通知に平文コードを載せない").doesNotContain(used)));
    }

    /** AC-12: バックアップコードの照合に失敗したときは監査 MFA_BACKUP_CODE_USED も通知も出ない。 */
    @Test
    @DisplayName("AC-12 照合失敗では使用監査も通知も出ない")
    void 照合失敗では使用監査も通知も出ない() throws Exception {
        MfaUser u = createTotpUser();
        MvcResult ok = validate(loginForMfaSession(u), "BACKUP_CODE", u.backupCodes().get(0));
        assertThat(ok.getResponse().getStatus()).as("AC-12 前提: %s", body(ok)).isEqualTo(200);
        awaitAudit(u.userId(), "MFA_BACKUP_CODE_USED", 1);
        awaitOutboxPayloads(u.userId(), "MFA_SECURITY_NOTICE", 1);

        MvcResult ng = validate(loginForMfaSession(u), "BACKUP_CODE", wrongBackupCode(u));

        assertThat(ng.getResponse().getStatus()).as("AC-12 前提: 失敗: %s", body(ng)).isEqualTo(400);
        org.awaitility.Awaitility.await().during(java.time.Duration.ofSeconds(1))
                .atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(auditCount(u.userId(), "MFA_BACKUP_CODE_USED")).as("AC-12 監査は増えない").isEqualTo(1);
                    assertThat(outboxCount(u.userId(), "MFA_SECURITY_NOTICE")).as("AC-12 通知は増えない").isEqualTo(1);
                });
    }

    // ═════════════════════════════════════════════════════════════
    // AC-35 監査種別・属性
    // ═════════════════════════════════════════════════════════════

    /** AC-35: AuditEventType に MFA_BACKUP_CODE_USED（MFA カテゴリ）がある。 */
    @Test
    @DisplayName("AC-35 AuditEventTypeにMFA_BACKUP_CODE_USEDがMFAカテゴリで存在する")
    void 監査種別MFA_BACKUP_CODE_USEDがある() {
        assertThat(AuditEventType.valueOf("MFA_BACKUP_CODE_USED").getCategory()).isEqualTo(AuditEventCategory.MFA);
    }

    /** AC-35/AC-37: TOTP 有効化（実 API）で監査 MFA_ENABLED に method=TOTP、本人宛通知メールが出る。 */
    @Test
    @DisplayName("AC-35 AC-37 TOTP有効化で監査MFA_ENABLEDにmethod=TOTPが残り通知メールが出る")
    void TOTP有効化で監査と通知が出る() throws Exception {
        long userId = createPlainUser();
        String email = userRepository.findById(userId).orElseThrow().getEmail();
        String secret = setupTotpViaApi(userId);

        MvcResult r = verifyTotpViaApi(userId, currentTotp(secret));

        assertThat(r.getResponse().getStatus()).as("AC-37 前提: 有効化成功: %s", body(r)).isEqualTo(200);
        awaitAudit(userId, "MFA_ENABLED", 1);
        assertThat(latestAuditMetadata(userId, "MFA_ENABLED", "$.method")).as("AC-35 method").isEqualTo("TOTP");
        awaitOutboxPayloads(userId, "MFA_SECURITY_NOTICE", 1);
        assertThat(outboxRecipients(userId, "MFA_SECURITY_NOTICE")).as("AC-37 宛先は本人").containsOnly(email);
    }
}
