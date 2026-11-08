package com.mannschaft.app.auth.mfa;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: MFA 失敗の監査行が失敗 TX のロールバック後も実 DB に残ること（AC-49）。
 *
 * <h2>このテストが守る欠陥</h2>
 * <p>MFA 検証の失敗は {@code BusinessException} の送出で応答するため、業務トランザクションはロールバックされる。
 * 監査を {@code @TransactionalEventListener(AFTER_COMMIT)}（既存の {@code AuditLogEventListener} の流儀）で
 * 記録すると、<b>コミットが起きないのでリスナーが一度も発火せず、失敗の監査行が 1 件も残らない</b>
 * （既存の {@code LoginFailedEvent} 監査がまさにこの形で、失敗経路では記録されていない）。
 * 総当たりの痕跡が監査から消えるのはセキュリティ上の実害である。</p>
 *
 * <h2>なぜ実 DB で検証するのか（false-green 回避）</h2>
 * <p>イベントの publish を verify するだけの UT は、リスナーがロールバックで発火しないことを観測できない。
 * 本テストは実 HTTP 経路で失敗させた<b>後に</b>、{@code audit_logs} を JDBC で読む（@Async 記録のため最大 5 秒待つ）。</p>
 *
 * <p>本クラスに {@code @Transactional} は付けない（付けるとテスト TX が全体を包み、ロールバックの挙動を観測できない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 MFA 失敗監査の永続化（AC-49）")
class MfaFailureAuditPersistenceIT extends MfaItSupport {

    /** AC-49: 不正な TOTP で validate が失敗した後も、audit_logs に MFA_FAILED（user_id 付き）が残る。 */
    @Test
    @DisplayName("AC-49 MFA失敗の監査行MFA_FAILEDが失敗応答の後もDBに残る")
    void MFA失敗の監査行はロールバック後も残る() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = validate(loginForMfaSession(u), "TOTP", wrongTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-49 前提: 失敗応答: %s", body(r)).isEqualTo(400);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'MFA_FAILED' AND user_id = ?",
                    Integer.class, u.userId());
            assertThat(n).as("AC-49 audit_logs に MFA_FAILED が user_id=%d で残る", u.userId()).isEqualTo(1);
        });
    }

    /** AC-49: バックアップコード失敗でも同様に残る（要素を問わない）。 */
    @Test
    @DisplayName("AC-49 バックアップコード失敗でもMFA_FAILEDが残る")
    void バックアップコード失敗でも監査行が残る() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = validate(loginForMfaSession(u), "BACKUP_CODE", wrongBackupCode(u));

        assertThat(r.getResponse().getStatus()).as("AC-49 前提: 失敗応答: %s", body(r)).isEqualTo(400);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'MFA_FAILED' AND user_id = ?",
                    Integer.class, u.userId());
            assertThat(n).as("AC-49 audit_logs に MFA_FAILED").isEqualTo(1);
        });
    }
}
