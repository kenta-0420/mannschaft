package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練: ログイン中の 2FA 無効化（{@code POST /api/v1/auth/2fa/disable}。AC-30〜33）。
 *
 * <p>盗難アクセストークンだけで 2FA を外されないよう、パスワードを持つユーザーには
 * <b>パスワード＋有効な要素（TOTP / 本人確認用メールOTP / バックアップコード）の両方</b>を要求する。
 * パスワード未設定（OAuth 専用等）のユーザーは要素のみ。失敗時は状態不変、後見切替中は 403。
 * 無効化してもセッションは維持する（裁可事項 3）。</p>
 *
 * <p>実 MySQL＋実 Security フィルタ越し（{@link MfaItSupport}）。外部境界の Valkey のみ代役。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 2FA 無効化（AC-30〜33）")
class MfaDisableIT extends MfaDisableRecoveryItSupport {

    @Autowired
    private AuthTokenService authTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // ═════════════════════════════════════════════════════════════
    // AC-30 本人確認の要件
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-30 本人確認（パスワード＋有効要素）")
    class Reauthentication {

        /** AC-30: hasPassword=true はパスワード＋TOTP で無効化できる。 */
        @Test
        @DisplayName("AC-30 パスワードとTOTPの両方で無効化できる")
        void パスワードとTOTPで無効化できる() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(200);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 無効化された").isFalse();
        }

        /** AC-30: hasPassword=true はパスワード＋バックアップコードでも無効化できる（端末紛失時）。 */
        @Test
        @DisplayName("AC-30 パスワードとバックアップコードで無効化できる")
        void パスワードとバックアップコードで無効化できる() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "BACKUP_CODE", u.backupCodes().get(3));

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(200);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 無効化された").isFalse();
        }

        /** AC-30: EMAIL 方式ユーザーはパスワード＋本人確認用メールOTP で無効化できる。 */
        @Test
        @DisplayName("AC-30 メール方式はパスワードと本人確認メールOTPで無効化できる")
        void メール方式はパスワードと本人確認メールOTPで無効化できる() throws Exception {
            MfaUser u = createEmailUser();
            String code = sendReauthOtpAndReadCode(u.userId());

            MvcResult r = disable(u.userId(), PASSWORD, "EMAIL", code);

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(200);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 無効化された").isFalse();
        }

        /** AC-30: hasPassword=true でパスワードを省くと、要素が正しくても拒否（盗難トークン対策）。 */
        @Test
        @DisplayName("AC-30 パスワード保持者がパスワードを省くと正しいコードでも拒否・状態不変")
        void パスワード保持者がパスワードを省くと拒否() throws Exception {
            MfaUser u = createTotpUser();
            String before = backupCodesJson(u.userId());

            MvcResult r = disable(u.userId(), null, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).as("AC-30 本人確認失敗").isEqualTo("AUTH_080");
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 有効のまま").isTrue();
            assertThat(backupCodesJson(u.userId())).as("AC-30 バックアップコード不変").isEqualTo(before);
        }

        /** AC-30: パスワードだけ（要素なし）は 400 で状態不変。 */
        @Test
        @DisplayName("AC-30 パスワードのみで要素なしは400・状態不変")
        void パスワードのみで要素なしは拒否() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", null);

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(400);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 有効のまま").isTrue();
        }

        /** AC-30: hasPassword=false（パスワード未設定）は要素のみで無効化できる。 */
        @Test
        @DisplayName("AC-30 パスワード未設定ユーザーは要素のみで無効化できる")
        void パスワード未設定ユーザーは要素のみで無効化できる() throws Exception {
            MfaUser u = createTotpUser();
            removePassword(u.userId());

            MvcResult r = disable(u.userId(), null, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(200);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 無効化された").isFalse();
        }

        /** AC-30: パスワード未設定でも要素が不正なら拒否。 */
        @Test
        @DisplayName("AC-30 パスワード未設定ユーザーでも不正コードは拒否・状態不変")
        void パスワード未設定ユーザーでも不正コードは拒否() throws Exception {
            MfaUser u = createTotpUser();
            removePassword(u.userId());

            MvcResult r = disable(u.userId(), null, "TOTP", wrongTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-30: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_080");
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 有効のまま").isTrue();
        }

        /** AC-30: レート制限 5 回/15 分。5 回失敗した後の 6 回目は正しい資格情報でも 429・状態不変。 */
        @Test
        @DisplayName("AC-30 5回失敗後の6回目は正しくても429・状態不変")
        void 五回失敗後の六回目は429() throws Exception {
            MfaUser u = createTotpUser();
            for (int i = 0; i < 5; i++) {
                MvcResult ng = disable(u.userId(), "wrong-" + i, "TOTP", currentTotp(u.totpSecret()));
                assertThat(ng.getResponse().getStatus()).as("AC-30 前提: %d 回目は本人確認失敗: %s", i + 1, body(ng))
                        .isEqualTo(400);
            }

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-30 6 回目はレート制限: %s", body(r)).isEqualTo(429);
            assertThat(twoFactorEnabled(u.userId())).as("AC-30 有効のまま").isTrue();
        }

        /** AC-30: 未認証は 401（@SelfScopedEndpoint・認証必須）。 */
        @Test
        @DisplayName("AC-30 未認証は401")
        void 未認証は401() throws Exception {
            MvcResult r = mockMvc.perform(post(DISABLE_PATH).servletPath(DISABLE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(factorBody(PASSWORD, "TOTP", "123456"))))
                    .andReturn();

            assertThat(r.getResponse().getStatus()).as("AC-30 未認証").isEqualTo(401);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-31 成功時の後始末
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-31 無効化成功時の後始末")
    class OnSuccess {

        /** AC-31: two_factor_auth 行は削除される（秘密鍵・バックアップコードのハッシュを残さない）。 */
        @Test
        @DisplayName("AC-31 two_factor_auth行が削除される")
        void 二要素認証行が削除される() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-31 前提: %s", body(r)).isEqualTo(200);
            assertThat(twoFactorRowCount(u.userId())).as("AC-31 行が消える").isZero();
        }

        /** AC-31: 発行済みの本人確認用メールOTP（Valkey）も消える。 */
        @Test
        @DisplayName("AC-31 発行済みの本人確認メールOTPキーが削除される")
        void 発行済みメールOTPキーが削除される() throws Exception {
            MfaUser u = createTotpUser();
            sendReauthOtpAndReadCode(u.userId());
            assertThat(emailOtpCodeKeys(u.userId())).as("AC-31 前提: 本人確認OTP のコード保持キーがある").isNotEmpty();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-31 前提: %s", body(r)).isEqualTo(200);
            assertThat(emailOtpCodeKeys(u.userId())).as("AC-31 OTP キーが残らない").isEmpty();
        }

        /** AC-31/AC-35: MfaDisabledEvent(USER) → 監査 MFA_DISABLED（method=TOTP, reason=USER）。 */
        @Test
        @DisplayName("AC-31 AC-35 監査MFA_DISABLEDにmethodとreason=USERが残る")
        void 監査MFA_DISABLEDが残る() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-31 前提: %s", body(r)).isEqualTo(200);
            awaitAudit(u.userId(), "MFA_DISABLED", 1);
            assertThat(latestAuditMetadata(u.userId(), "MFA_DISABLED", "$.reason")).as("AC-35 reason").isEqualTo("USER");
            assertThat(latestAuditMetadata(u.userId(), "MFA_DISABLED", "$.method")).as("AC-35 method").isEqualTo("TOTP");
        }

        /** AC-31/AC-37: 本人宛の MFA_SECURITY_NOTICE が enqueue される。 */
        @Test
        @DisplayName("AC-31 AC-37 本人宛のセキュリティ通知メールがenqueueされる")
        void 通知メールがenqueueされる() throws Exception {
            MfaUser u = createTotpUser();

            MvcResult r = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-31 前提: %s", body(r)).isEqualTo(200);
            awaitOutboxPayloads(u.userId(), "MFA_SECURITY_NOTICE", 1);
            assertThat(outboxRecipients(u.userId(), "MFA_SECURITY_NOTICE")).as("AC-37 宛先は本人")
                    .containsOnly(u.email());
        }

        /** AC-31: 無効化後の次回ログインは MFA 不要（パスワードだけでトークン Cookie が出る）。 */
        @Test
        @DisplayName("AC-31 無効化後の次回ログインはMFA不要")
        void 無効化後の次回ログインはMFA不要() throws Exception {
            MfaUser u = createTotpUser();
            MvcResult d = disable(u.userId(), PASSWORD, "TOTP", currentTotp(u.totpSecret()));
            assertThat(d.getResponse().getStatus()).as("AC-31 前提: %s", body(d)).isEqualTo(200);

            MvcResult r = login(u.email());

            assertThat(r.getResponse().getStatus()).as("AC-31: %s", body(r)).isEqualTo(200);
            assertThat(json(r).path("data").path("mfaRequired").asBoolean(false)).as("AC-31 mfaRequired").isFalse();
            assertThat(cookieValue(r, "access_token")).as("AC-31 アクセストークン Cookie").isNotBlank();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-32 失敗時は状態不変
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-32 失敗時の状態不変・未有効・後見切替")
    class Failures {

        /** AC-32: パスワード誤りは 400（AUTH_080）・行もバックアップコードも不変。 */
        @Test
        @DisplayName("AC-32 パスワード誤りは400で状態不変")
        void パスワード誤りは状態不変() throws Exception {
            MfaUser u = createTotpUser();
            String before = backupCodesJson(u.userId());

            MvcResult r = disable(u.userId(), "Wrong-Passw0rd!", "TOTP", currentTotp(u.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-32: %s", body(r)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("AUTH_080");
            assertThat(twoFactorEnabled(u.userId())).as("AC-32 有効のまま").isTrue();
            assertThat(backupCodesJson(u.userId())).as("AC-32 バックアップコード不変").isEqualTo(before);
        }

        /** AC-32: コード誤りは 400（AUTH_080）・状態不変。バックアップコード誤りも同じ。 */
        @Test
        @DisplayName("AC-32 コード誤りは400で状態不変")
        void コード誤りは状態不変() throws Exception {
            MfaUser u = createTotpUser();
            String before = backupCodesJson(u.userId());

            MvcResult totp = disable(u.userId(), PASSWORD, "TOTP", wrongTotp(u.totpSecret()));
            MvcResult backup = disable(u.userId(), PASSWORD, "BACKUP_CODE", wrongBackupCode(u));

            assertThat(totp.getResponse().getStatus()).as("AC-32 TOTP誤り: %s", body(totp)).isEqualTo(400);
            assertThat(errorCode(totp)).isEqualTo("AUTH_080");
            assertThat(backup.getResponse().getStatus()).as("AC-32 バックアップ誤り: %s", body(backup)).isEqualTo(400);
            assertThat(errorCode(backup)).isEqualTo("AUTH_080");
            assertThat(twoFactorEnabled(u.userId())).as("AC-32 有効のまま").isTrue();
            assertThat(backupCodesJson(u.userId())).as("AC-32 バックアップコード不変").isEqualTo(before);
        }

        /** AC-32: 2FA 未有効のユーザーは「未有効」エラー（409 AUTH_079）。 */
        @Test
        @DisplayName("AC-32 未有効ユーザーは409 AUTH_079")
        void 未有効ユーザーは未有効エラー() throws Exception {
            long userId = createPlainUser();

            MvcResult r = disable(userId, PASSWORD, "TOTP", "123456");

            assertThat(r.getResponse().getStatus()).as("AC-32: %s", body(r)).isEqualTo(409);
            assertThat(errorCode(r)).isEqualTo("AUTH_079");
        }

        /** AC-32: 後見切替（acting-as）中は正しい資格情報でも 403・状態不変。 */
        @Test
        @DisplayName("AC-32 後見切替中は403で状態不変")
        void 後見切替中は403() throws Exception {
            MfaUser child = createTotpUser();
            long guardianId = createGuardianActingAs(child.userId());

            MvcResult r = disableActingAs(guardianId, child.userId(), PASSWORD, "TOTP", currentTotp(child.totpSecret()));

            assertThat(r.getResponse().getStatus()).as("AC-32: %s", body(r)).isEqualTo(403);
            assertThat(twoFactorEnabled(child.userId())).as("AC-32 子の 2FA は有効のまま").isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-33 セッション維持（裁可事項 3）
    // ═════════════════════════════════════════════════════════════

    /**
     * AC-33: 無効化してもセッションは維持する。現在のアクセストークンも、別端末のリフレッシュトークンも有効なまま
     * （回復フローのような全セッション失効・user_invalidated_at 更新をしない）。
     *
     * <p>実 JWT（Bearer）で認証し、JWT フィルタの失効判定まで含めて検証する。</p>
     */
    @Test
    @DisplayName("AC-33 無効化後も現在のアクセストークンと他端末のリフレッシュトークンが有効")
    void 無効化後もセッションが維持される() throws Exception {
        MfaUser u = createTotpUser();
        String accessToken = authTokenService.issueAccessToken(u.userId(), List.of());
        String currentRefresh = seedRefreshToken(u.userId());
        String otherDeviceRefresh = seedRefreshToken(u.userId());
        assertThat(activeRefreshTokenCount(u.userId())).as("AC-33 前提").isEqualTo(2);

        MvcResult r = mockMvc.perform(post(DISABLE_PATH).servletPath(DISABLE_PATH)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                factorBody(PASSWORD, "TOTP", currentTotp(u.totpSecret())))))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as("AC-33 前提: 無効化成功: %s", body(r)).isEqualTo(200);
        assertThat(activeRefreshTokenCount(u.userId())).as("AC-33 リフレッシュトークンは失効しない").isEqualTo(2);
        assertThat(valkey.rawGet(USER_INVALIDATED_KEY_PREFIX + u.userId()))
                .as("AC-33 user_invalidated_at を立てない").isNull();

        MvcResult st = mockMvc.perform(get(STATUS_PATH).servletPath(STATUS_PATH)
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();
        assertThat(st.getResponse().getStatus()).as("AC-33 現在のアクセストークンで引き続き認証できる: %s", body(st))
                .isEqualTo(200);
        JsonNode data = json(st).path("data");
        assertThat(data.path("enabled").asBoolean(true)).as("AC-33 状態は無効").isFalse();

        MvcResult refreshed = refreshWith(otherDeviceRefresh);
        assertThat(refreshed.getResponse().getStatus()).as("AC-33 他端末のリフレッシュが通る: %s", body(refreshed))
                .isEqualTo(200);
        assertThat(currentRefresh).isNotBlank();
    }

    /** ログイン済み端末を模したリフレッシュトークン行を作り、平文を返す。 */
    private String seedRefreshToken(long userId) {
        String raw = authTokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(authTokenService.hashToken(raw))
                .jti(UUID.randomUUID().toString())
                .rememberMe(false)
                .userAgent(USER_AGENT)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());
        return raw;
    }
}
