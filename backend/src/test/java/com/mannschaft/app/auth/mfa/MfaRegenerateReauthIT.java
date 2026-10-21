package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261012-0910 試練: バックアップコード再生成の本人確認（AC-44）と監査（AC-36）。
 *
 * <p>{@code POST /api/v1/auth/2fa/backup-codes/regenerate} は従来本人確認なしで、盗難アクセストークンだけで
 * バックアップコードを差し替え・入手できた（F01.1 §254 違反）。本人確認として「現在のパスワード」または
 * 「有効な要素（TOTP / 本人確認用メールOTP / バックアップコード）」を要求する（裁可事項 8）。
 * 本人確認なし・不正ならコードは 1 文字も変わらず、成功時は旧コード全失効＋新 8 件。後見切替中は 403。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 バックアップコード再生成の本人確認（AC-44・AC-36）")
class MfaRegenerateReauthIT extends MfaDisableRecoveryItSupport {

    /** AC-44: 本文なし（本人確認なし）は 400 AUTH_080・コード不変。 */
    @Test
    @DisplayName("AC-44 本人確認なしは400でコード不変")
    void 本人確認なしは拒否されコード不変() throws Exception {
        MfaUser u = createTotpUser();
        String before = backupCodesJson(u.userId());

        MvcResult noBody = regenerate(u.userId(), null);
        MvcResult emptyBody = regenerate(u.userId(), Map.of());

        assertThat(noBody.getResponse().getStatus()).as("AC-44 本文なし: %s", body(noBody)).isEqualTo(400);
        assertThat(emptyBody.getResponse().getStatus()).as("AC-44 空本文: %s", body(emptyBody)).isEqualTo(400);
        assertThat(errorCode(emptyBody)).isEqualTo("AUTH_080");
        assertThat(backupCodesJson(u.userId())).as("AC-44 コード不変").isEqualTo(before);
    }

    /** AC-44: パスワード誤り・コード誤りは 400 AUTH_080・コード不変。 */
    @Test
    @DisplayName("AC-44 パスワード誤りやコード誤りは400でコード不変")
    void 不正な本人確認は拒否されコード不変() throws Exception {
        MfaUser u = createTotpUser();
        String before = backupCodesJson(u.userId());

        MvcResult badPw = regenerate(u.userId(), factorBody("Wrong-Passw0rd!", null, null));
        MvcResult badTotp = regenerate(u.userId(), factorBody(null, "TOTP", wrongTotp(u.totpSecret())));
        MvcResult badBackup = regenerate(u.userId(), factorBody(null, "BACKUP_CODE", wrongBackupCode(u)));

        for (MvcResult r : List.of(badPw, badTotp, badBackup)) {
            assertThat(r.getResponse().getStatus()).as("AC-44 不正: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).as("AC-44 本人確認失敗").isEqualTo("AUTH_080");
        }
        assertThat(backupCodesJson(u.userId())).as("AC-44 コード不変").isEqualTo(before);
    }

    /** AC-44: 正しいパスワードで成功。旧 8 件はすべて失効し、新 8 件（応答の平文と DB のハッシュが対応）。 */
    @Test
    @DisplayName("AC-44 パスワードで成功し旧コード全失効と新8件")
    void パスワードで再生成すると旧コード全失効と新8件() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = regenerate(u.userId(), factorBody(PASSWORD, null, null));

        assertThat(r.getResponse().getStatus()).as("AC-44: %s", body(r)).isEqualTo(200);
        assertNewCodesReplaceOld(u, r);
    }

    /** AC-44: パスワードの代わりに有効な TOTP でも成功（PW or 有効要素）。 */
    @Test
    @DisplayName("AC-44 有効なTOTPでも再生成できる")
    void TOTPで再生成できる() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = regenerate(u.userId(), factorBody(null, "TOTP", currentTotp(u.totpSecret())));

        assertThat(r.getResponse().getStatus()).as("AC-44: %s", body(r)).isEqualTo(200);
        assertNewCodesReplaceOld(u, r);
    }

    /** AC-44: EMAIL 方式ユーザーは本人確認用メールOTP でも成功。 */
    @Test
    @DisplayName("AC-44 メール方式は本人確認メールOTPで再生成できる")
    void メール方式は本人確認メールOTPで再生成できる() throws Exception {
        MfaUser u = createEmailUser();
        String code = sendReauthOtpAndReadCode(u.userId());

        MvcResult r = regenerate(u.userId(), factorBody(null, "EMAIL", code));

        assertThat(r.getResponse().getStatus()).as("AC-44: %s", body(r)).isEqualTo(200);
        assertNewCodesReplaceOld(u, r);
    }

    /** AC-44: 後見切替（acting-as）中は正しいパスワードでも 403・コード不変。 */
    @Test
    @DisplayName("AC-44 後見切替中は403でコード不変")
    void 後見切替中は403() throws Exception {
        MfaUser child = createTotpUser();
        long guardianId = createGuardianActingAs(child.userId());
        String before = backupCodesJson(child.userId());

        MvcResult r = regenerateActingAs(guardianId, child.userId(), factorBody(PASSWORD, null, null));

        assertThat(r.getResponse().getStatus()).as("AC-44: %s", body(r)).isEqualTo(403);
        assertThat(backupCodesJson(child.userId())).as("AC-44 コード不変").isEqualTo(before);
    }

    /** AC-44: 2FA 未有効のユーザーは 409 AUTH_079。 */
    @Test
    @DisplayName("AC-44 未有効ユーザーは409 AUTH_079")
    void 未有効ユーザーは409() throws Exception {
        long userId = createPlainUser();

        MvcResult r = regenerate(userId, factorBody(PASSWORD, null, null));

        assertThat(r.getResponse().getStatus()).as("AC-44: %s", body(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("AUTH_079");
    }

    /** AC-36: 再生成成功で MfaBackupCodesRegeneratedEvent → 監査 MFA_BACKUP_CODES_REGENERATED が残る。 */
    @Test
    @DisplayName("AC-36 再生成で監査MFA_BACKUP_CODES_REGENERATEDが残る")
    void 再生成で監査が残る() throws Exception {
        MfaUser u = createTotpUser();

        MvcResult r = regenerate(u.userId(), factorBody(PASSWORD, null, null));

        assertThat(r.getResponse().getStatus()).as("AC-36 前提: %s", body(r)).isEqualTo(200);
        awaitAudit(u.userId(), "MFA_BACKUP_CODES_REGENERATED", 1);
    }

    /** AC-36: 本人確認に失敗した再生成は監査を残さない（成功事象のみ記録）。 */
    @Test
    @DisplayName("AC-36 本人確認失敗では再生成監査を残さない")
    void 失敗した再生成は監査を残さない() throws Exception {
        MfaUser u = createTotpUser();
        MvcResult ok = regenerate(u.userId(), factorBody(PASSWORD, null, null));
        assertThat(ok.getResponse().getStatus()).as("AC-36 前提: %s", body(ok)).isEqualTo(200);
        awaitAudit(u.userId(), "MFA_BACKUP_CODES_REGENERATED", 1);

        MvcResult ng = regenerate(u.userId(), factorBody("Wrong-Passw0rd!", null, null));

        assertThat(ng.getResponse().getStatus()).as("AC-36 前提: 失敗: %s", body(ng)).isEqualTo(400);
        // 非同期記録の遅着も拾うため、1 秒間ずっと 1 件のままであることを確かめる。
        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(auditCount(u.userId(), "MFA_BACKUP_CODES_REGENERATED"))
                        .as("AC-36 失敗分は増えない").isEqualTo(1));
    }

    private void assertNewCodesReplaceOld(MfaUser u, MvcResult r) throws Exception {
        JsonNode codesNode = json(r).path("data").path("backupCodes");
        List<String> newCodes = new ArrayList<>();
        codesNode.forEach(n -> newCodes.add(n.asText()));
        assertThat(newCodes).as("AC-44 新コード 8 件").hasSize(8).allMatch(c -> c.matches("\\d{8}"));
        assertThat(newCodes).as("AC-44 新コードは旧コードと重ならない").doesNotContainAnyElementsOf(u.backupCodes());

        List<String> hashes = objectMapper.readValue(backupCodesJson(u.userId()),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(hashes).as("AC-44 DB は 8 件").hasSize(8);
        for (String old : u.backupCodes()) {
            assertThat(hashes.stream().noneMatch(h -> passwordEncoder.matches(old, h)))
                    .as("AC-44 旧コード %s は失効", old).isTrue();
        }
        for (String fresh : newCodes) {
            assertThat(hashes.stream().anyMatch(h -> passwordEncoder.matches(fresh, h)))
                    .as("AC-44 新コード %s が DB のハッシュと対応", fresh).isTrue();
        }
        MvcResult reuse = validate(loginForMfaSession(u), "BACKUP_CODE", u.backupCodes().get(0));
        assertThat(reuse.getResponse().getStatus()).as("AC-44 旧コードではログインできない: %s", body(reuse))
                .isEqualTo(400);
    }
}
