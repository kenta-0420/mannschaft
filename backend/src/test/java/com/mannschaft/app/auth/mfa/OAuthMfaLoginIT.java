package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.util.SessionHashUtil;
import com.mannschaft.app.support.test.FakeOAuthProviders;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0911 試練: OAuth ログインが 2FA を迂回する欠陥の是正（HTTP 境界の受け入れテスト）。
 *
 * <p><b>構成</b>: 実 Controller → 実 Service → 実 MySQL（Testcontainers）・実 Security フィルタ（{@link MfaItSupport}）。
 * 外部境界のみ代役: Valkey は {@link MfaValkeyFake}、OAuth プロバイダ（Google / LINE / Apple）は {@link FakeOAuthProviders}
 * （JDK 標準の HttpServer。共通基底 {@code AbstractMySqlIntegrationTest} の {@code @DynamicPropertySource} で向けるため、
 * 本クラスは基底と同じ構成キー・同じ ApplicationContext を使う）。
 * 偽プロバイダは認可コードに {@code <sub>~<email>} を埋めて返すため、テストごとに別の OAuth 利用者を作れる。</p>
 *
 * <p>本テストは {@code @Transactional} にしない（AFTER_COMMIT 監査を観測するため）。</p>
 *
 * <p>対象 AC: AC-1〜7・AC-12・AC-14〜17（AC-13 は FE 単体、AC-8〜11 は実機）。判断①（method=入口方式）・②（監査 metadata）。</p>
 */
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0911 OAuth ログインの 2FA ゲート（HTTP 境界）")
class OAuthMfaLoginIT extends MfaEmailOtpItSupport {

    private static final String OAUTH_PATH = "/api/v1/auth/oauth/";
    private static final String CONFIRM_PATH = "/api/v1/auth/oauth/link/confirm";
    private static final String UA_LOGIN = "OAuthMfaIT/1.0 (login)";
    private static final String UA_VALIDATE = "OAuthMfaIT/1.0 (validate)";
    private static final String MFA_METHOD_KEY_PREFIX = "mannschaft:auth:mfa_session_method:";

    @Autowired
    private ApplicationEvents events;
    @Autowired
    private OAuthAccountRepository oauthAccountRepository;
    @Autowired
    private OAuthLinkTokenRepository oauthLinkTokenRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private final List<Long> oauthUserIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanUpOAuthRows() {
        for (Long userId : oauthUserIds) {
            jdbcTemplate.update("DELETE FROM oauth_link_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM oauth_accounts WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM audit_logs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
        }
        oauthUserIds.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // フィクスチャ・HTTP
    // ─────────────────────────────────────────────────────────────

    /** ユーザーに OAuth 連携を張り、その sub を返す。 */
    private String link(long userId, OAuthAccountEntity.OAuthProvider provider) {
        oauthUserIds.add(userId);
        String sub = "it-sub-" + UUID.randomUUID();
        oauthAccountRepository.save(OAuthAccountEntity.builder()
                .userId(userId).provider(provider).providerUserId(sub)
                .providerEmail("oauth-mfa-" + UUID.randomUUID() + "@example.com").build());
        return sub;
    }

    private MvcResult oauthLogin(OAuthAccountEntity.OAuthProvider provider, String sub, String email) throws Exception {
        return mockMvc.perform(withIp(post(OAUTH_PATH + provider.name().toLowerCase())
                                .servletPath(OAUTH_PATH + provider.name().toLowerCase()), ip)
                        .param("code", FakeOAuthProviders.code(sub, email))
                        .header("User-Agent", UA_LOGIN))
                .andReturn();
    }

    /** OAuth ログインで MFA 要求を受け、MFA セッショントークンを返す（2FA ゲートの前提確認を兼ねる）。 */
    private String oauthMfaSession(OAuthAccountEntity.OAuthProvider provider, String sub) throws Exception {
        MvcResult r = oauthLogin(provider, sub, "oauth-mfa-" + UUID.randomUUID() + "@example.com");
        assertThat(r.getResponse().getStatus()).as("OAuth ログインは 200: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-1 2FA 有効なら MFA 要求: %s", body(r)).isTrue();
        String token = data.path("mfaSessionToken").asText(null);
        assertThat(token).isNotBlank();
        return token;
    }

    private MvcResult validateFrom(String session, String method, String code) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("mfaSessionToken", session);
        b.put("method", method);
        b.put("code", code);
        return mockMvc.perform(withIp(post(VALIDATE_PATH).servletPath(VALIDATE_PATH), ip)
                        .header("User-Agent", UA_VALIDATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andReturn();
    }

    private MvcResult confirm(String rawToken) throws Exception {
        return mockMvc.perform(withIp(post(CONFIRM_PATH).servletPath(CONFIRM_PATH), ip)
                        .param("token", rawToken)
                        .header("User-Agent", UA_LOGIN))
                .andReturn();
    }

    /** 連携確認トークン（メールで届く平文）を発行済みの状態を作る。 */
    private String linkTokenFor(long userId, String sub) {
        oauthUserIds.add(userId);
        String raw = "link-" + UUID.randomUUID();
        oauthLinkTokenRepository.save(OAuthLinkTokenEntity.builder()
                .userId(userId)
                .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                .providerUserId(sub)
                .providerEmail("oauth-mfa-" + UUID.randomUUID() + "@example.com")
                .tokenHash(authTokenService.hashToken(raw))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build());
        return raw;
    }

    private List<LoginSuccessEvent> loginSuccess(long userId) {
        return events.stream(LoginSuccessEvent.class).filter(e -> e.getUserId() == userId).toList();
    }

    private Map<String, Object> latestRefreshRow(long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT jti, ip_address, user_agent, device_fingerprint, expires_at, created_at "
                        + "FROM refresh_tokens WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
    }

    private LocalDateTime lastLoginAt(long userId) {
        return jdbcTemplate.queryForObject("SELECT last_login_at FROM users WHERE id = ?", LocalDateTime.class, userId);
    }

    private Map<String, Object> awaitLoginAuditRow(long userId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS'",
                        Integer.class, userId)).isGreaterThanOrEqualTo(1));
        return jdbcTemplate.queryForMap(
                "SELECT ip_address, user_agent, session_hash, CAST(metadata AS CHAR) AS metadata FROM audit_logs "
                        + "WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS' ORDER BY id DESC LIMIT 1", userId);
    }

    private int loginAuditCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type = 'LOGIN_SUCCESS'", Integer.class, userId);
        return n == null ? 0 : n;
    }

    // ═════════════════════════════════════════════════════════════
    // AC-1 / AC-16 既存連携ログインの 2FA ゲート（全プロバイダ）
    // ═════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GOOGLE", "LINE", "APPLE"})
    @DisplayName("AC-1/AC-16: 2FA(TOTP) 有効ユーザーの既存連携ログインは MFA 要求のみ返し、トークン・Cookie・LOGIN_SUCCESS・last_login_at 更新なし")
    void 既存連携ログインはMFA要求(String providerName) throws Exception {
        OAuthAccountEntity.OAuthProvider provider = OAuthAccountEntity.OAuthProvider.valueOf(providerName);
        MfaUser u = totpUser();
        String sub = link(u.userId(), provider);

        MvcResult r = oauthLogin(provider, sub, u.email());

        assertThat(r.getResponse().getStatus()).as("200: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-1 MFA 要求: %s", body(r)).isTrue();
        assertThat(data.path("mfaSessionToken").asText("")).isNotBlank();
        assertThat(data.path("primaryMethod").asText()).isEqualTo("TOTP");
        assertThat(data.path("availableMethods").toString()).isEqualTo("[\"TOTP\",\"BACKUP_CODE\",\"EMAIL\"]");
        assertThat(data.has("accessToken")).as("AC-1 トークンを返さない").isFalse();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).as("AC-1 Cookie を付けない").isEmpty();
        assertThat(refreshTokenCount(u.userId())).as("AC-1 refresh_tokens は増えない").isZero();
        assertThat(loginSuccess(u.userId())).as("AC-1 LOGIN_SUCCESS は出ない").isEmpty();
        assertThat(lastLoginAt(u.userId())).as("AC-1 last_login_at は不変").isNull();
    }

    @Test
    @DisplayName("AC-1: EMAIL 主手段の 2FA ユーザーは primaryMethod=EMAIL・提示要素 [EMAIL, BACKUP_CODE]")
    void 既存連携ログイン_EMAIL主手段() throws Exception {
        MfaUser u = emailUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub, u.email());

        JsonNode data = json(r).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-1: %s", body(r)).isTrue();
        assertThat(data.path("primaryMethod").asText()).isEqualTo("EMAIL");
        assertThat(data.path("availableMethods").toString()).isEqualTo("[\"EMAIL\",\"BACKUP_CODE\"]");
        assertThat(refreshTokenCount(u.userId())).isZero();
    }

    @Test
    @DisplayName("AC-7 改: OAuth 由来のセッションキーの値は userId のまま（旧プロセス互換）、入口方式は別キー OAUTH_GOOGLE")
    void OAuth由来セッションのValkey形式() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));

        String hash = authTokenService.hashToken(session);
        assertThat(valkey.rawGet(MFA_SESSION_KEY_PREFIX + hash)).isEqualTo(String.valueOf(u.userId()));
        assertThat(valkey.rawGet(MFA_METHOD_KEY_PREFIX + hash)).isEqualTo("OAUTH_GOOGLE");
    }

    // ═════════════════════════════════════════════════════════════
    // AC-2 OAuth 由来 challenge の完走
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2/判断①②: OAuth 由来 challenge を TOTP で完了すると Cookie とトークン、LOGIN_SUCCESS(method=OAUTH_GOOGLE・検証要求の ip/UA・session_hash=SHA-256(jti)・metadata)")
    void OAuth由来challengeの完走() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));

        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-2 200: %s", body(r)).isEqualTo(200);
        assertThat(json(r).path("data").path("accessToken").asText("")).isNotBlank();
        assertThat(String.join(";", r.getResponse().getHeaders("Set-Cookie")))
                .contains("access_token=").contains("refresh_token=");
        List<LoginSuccessEvent> ev = loginSuccess(u.userId());
        assertThat(ev).as("AC-2 LOGIN_SUCCESS は 1 件").hasSize(1);
        Map<String, Object> rt = latestRefreshRow(u.userId());
        assertThat(ev.get(0).getMethod()).as("判断①: 入口方式").isEqualTo("OAUTH_GOOGLE");
        assertThat(ev.get(0).getIpAddress()).isEqualTo(ip);
        assertThat(ev.get(0).getUserAgent()).isEqualTo(UA_VALIDATE);
        assertThat(ev.get(0).getSessionHash()).isEqualTo(SessionHashUtil.hash((String) rt.get("jti")));
        assertThat(rt.get("ip_address")).isEqualTo(ip);
        assertThat(rt.get("user_agent")).isEqualTo(UA_VALIDATE);

        Map<String, Object> audit = awaitLoginAuditRow(u.userId());
        assertThat(audit.get("ip_address")).isEqualTo(ip);
        assertThat(audit.get("user_agent")).isEqualTo(UA_VALIDATE);
        assertThat(audit.get("session_hash")).isEqualTo(SessionHashUtil.hash((String) rt.get("jti")));
        assertThat(audit.get("metadata")).as("判断②: metadata").isNotNull();
        JsonNode metadata = objectMapper.readTree((String) audit.get("metadata"));
        assertThat(metadata.path("method").asText()).isEqualTo("OAUTH_GOOGLE");
        assertThat(metadata.path("mfa").asBoolean()).isTrue();
        assertThat(metadata.path("mfa_factor").asText()).isEqualTo("TOTP");
    }

    // ═════════════════════════════════════════════════════════════
    // AC-3 2FA 無効ユーザーの回帰
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3: 2FA 無効ユーザーの既存連携ログインは従来どおり即トークン発行・LOGIN_SUCCESS(method=OAUTH_GOOGLE)")
    void 二FA無効ユーザーは従来どおり() throws Exception {
        long userId = plainUser();
        String sub = link(userId, OAuthAccountEntity.OAuthProvider.GOOGLE);

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub, "x@example.com");

        assertThat(r.getResponse().getStatus()).as("AC-3: %s", body(r)).isEqualTo(200);
        assertThat(json(r).path("data").path("accessToken").asText("")).isNotBlank();
        assertThat(json(r).path("data").path("mfaRequired").asBoolean(false)).isFalse();
        assertThat(loginSuccess(userId)).singleElement()
                .satisfies(e -> assertThat(e.getMethod()).isEqualTo("OAUTH_GOOGLE"));
    }

    // ═════════════════════════════════════════════════════════════
    // AC-4 / AC-14 連携確認経路
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4: 2FA 有効ユーザーの /oauth/link/confirm は連携を作ったうえで MFA 要求のみ返し、トークンなし。連携トークン再利用は AUTH_031")
    void 連携確認は2FA有効ならMFA要求() throws Exception {
        MfaUser u = totpUser();
        String sub = "it-sub-" + UUID.randomUUID();
        String raw = linkTokenFor(u.userId(), sub);

        MvcResult r = confirm(raw);

        assertThat(r.getResponse().getStatus()).as("AC-4: %s", body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-4 MFA 要求: %s", body(r)).isTrue();
        assertThat(data.has("accessToken")).isFalse();
        assertThat(refreshTokenCount(u.userId())).isZero();
        assertThat(loginSuccess(u.userId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE user_id = ? AND provider_user_id = ?", Integer.class, u.userId(), sub))
                .as("AC-4 連携は作成される").isEqualTo(1);

        MvcResult reuse = confirm(raw);
        assertThat(errorCode(reuse)).as("AC-4 連携トークン再利用").isEqualTo("AUTH_031");
    }

    @Test
    @DisplayName("AC-14: 連携確認由来の challenge を完了すると method=OAUTH_GOOGLE・検証要求の ip/UA の LOGIN_SUCCESS")
    void 連携確認由来challengeの完走() throws Exception {
        MfaUser u = totpUser();
        String raw = linkTokenFor(u.userId(), "it-sub-" + UUID.randomUUID());
        JsonNode data = json(confirm(raw)).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("前提: MFA 要求").isTrue();

        MvcResult r = validateFrom(data.path("mfaSessionToken").asText(), "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-14: %s", body(r)).isEqualTo(200);
        assertThat(loginSuccess(u.userId())).singleElement().satisfies(e -> {
            assertThat(e.getMethod()).isEqualTo("OAUTH_GOOGLE");
            assertThat(e.getIpAddress()).isEqualTo(ip);
            assertThat(e.getUserAgent()).isEqualTo(UA_VALIDATE);
        });
    }

    @Test
    @DisplayName("AC-14: 2FA 無効ユーザーの連携確認は即発行し、要求元 ip/UA の LOGIN_SUCCESS(method=OAUTH_GOOGLE) と refresh_tokens の ip/UA")
    void 連携確認は2FA無効なら即発行() throws Exception {
        long userId = plainUser();
        String raw = linkTokenFor(userId, "it-sub-" + UUID.randomUUID());

        MvcResult r = confirm(raw);

        assertThat(r.getResponse().getStatus()).as("AC-14: %s", body(r)).isEqualTo(200);
        assertThat(json(r).path("data").path("accessToken").asText("")).isNotBlank();
        assertThat(loginSuccess(userId)).as("AC-14 LOGIN_SUCCESS").singleElement().satisfies(e -> {
            assertThat(e.getMethod()).isEqualTo("OAUTH_GOOGLE");
            assertThat(e.getIpAddress()).isEqualTo(ip);
            assertThat(e.getUserAgent()).isEqualTo(UA_LOGIN);
        });
        Map<String, Object> rt = latestRefreshRow(userId);
        assertThat(rt.get("ip_address")).isEqualTo(ip);
        assertThat(rt.get("user_agent")).isEqualTo(UA_LOGIN);
    }

    @Test
    @DisplayName("AC-14: 連携確認後に 2FA を途中放棄しても、次の OAuth ログインで再び MFA 要求を受けられる")
    void 連携確認後の途中放棄から再ログイン() throws Exception {
        MfaUser u = totpUser();
        String sub = "it-sub-" + UUID.randomUUID();
        assertThat(json(confirm(linkTokenFor(u.userId(), sub))).path("data").path("mfaRequired").asBoolean())
                .as("前提: MFA 要求").isTrue();

        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);
        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-14 再ログインで完走: %s", body(r)).isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-5 試行上限・ロック・要素・他人
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: OAuth 由来セッションも 5 回目の失敗で AUTH_075・以後は正しいコードでも AUTH_073")
    void OAuth由来セッションの試行上限() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));
        for (int i = 0; i < 4; i++) {
            assertThat(errorCode(validateFrom(session, "TOTP", wrongTotp(u.totpSecret())))).isEqualTo("AUTH_074");
        }

        MvcResult fifth = validateFrom(session, "TOTP", wrongTotp(u.totpSecret()));
        MvcResult after = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(errorCode(after)).as("AC-12 上限失敗後の再利用は拒否").isEqualTo("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).isZero();
    }

    @Test
    @DisplayName("AC-5 改: PASSWORD と OAuth のセッション失敗は合算され、ロック後は既発行の OAuth セッションも全要素（TOTP/BACKUP/EMAIL）で AUTH_003")
    void 入口方式をまたぐ失敗合算とロック() throws Exception {
        MfaUser u = totpUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);
        String preIssued = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);
        String passwordSession = loginForMfaSession(u);
        String oauthSession = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);

        for (int i = 0; i < 5; i++) {
            validateFrom(passwordSession, "TOTP", wrongTotp(u.totpSecret()));
        }
        for (int i = 0; i < 5; i++) {
            validateFrom(oauthSession, "BACKUP_CODE", wrongBackupCode(u));
        }

        assertThat(errorCode(validateFrom(preIssued, "TOTP", currentTotp(u.totpSecret()))))
                .as("AC-5 ロック後は TOTP も拒否").isEqualTo("AUTH_003");
        assertThat(errorCode(validateFrom(preIssued, "BACKUP_CODE", u.backupCodes().get(0))))
                .as("AC-5 ロック後はバックアップコードも拒否").isEqualTo("AUTH_003");
        assertThat(errorCode(validateFrom(preIssued, "EMAIL", "123456")))
                .as("AC-5 ロック後はメールOTP も拒否").isEqualTo("AUTH_003");
        assertThat(refreshTokenCount(u.userId())).isZero();
    }

    @Test
    @DisplayName("AC-5 改: A の OAuth 由来セッション＋B の認証要素では成功しない（AUTH_074・発行なし）")
    void 他人の要素では成功しない() throws Exception {
        MfaUser a = totpUser();
        MfaUser b = totpUser();
        String sessionA = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(a.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));

        MvcResult r = validateFrom(sessionA, "TOTP", currentTotp(b.totpSecret()));

        assertThat(errorCode(r)).isEqualTo("AUTH_074");
        assertThat(refreshTokenCount(a.userId())).isZero();
        assertThat(refreshTokenCount(b.userId())).isZero();
    }

    @Test
    @DisplayName("AC-5 改: 9 回失敗後の「10 回目の失敗」と「正しいコードの成功」を並列に投げても、ロック確定後は発行されず、発行は 1 件以下で整合する")
    void 十回目の失敗と成功の並列はロックと整合する() throws Exception {
        MfaUser u = totpUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);
        // ユーザー単位の失敗を 9 回（OAuth セッション 5 回＋パスワードセッション 4 回。セッション上限 5 回には達しない構成）
        String failA = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);
        String failB = loginForMfaSession(u);
        for (int i = 0; i < 5; i++) {
            validateFrom(failA, "TOTP", wrongTotp(u.totpSecret()));
        }
        for (int i = 0; i < 4; i++) {
            validateFrom(failB, "TOTP", wrongTotp(u.totpSecret()));
        }
        String failing = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);
        String winning = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);
        String wrong = wrongTotp(u.totpSecret());
        String correct = currentTotp(u.totpSecret());

        List<String> outcomes = runConcurrentlyOutcomes(
                () -> auth2faService.validateMfaLogin(failing, MfaVerificationMethod.TOTP, wrong, ip, UA_VALIDATE),
                () -> auth2faService.validateMfaLogin(winning, MfaVerificationMethod.TOTP, correct, ip, UA_VALIDATE));

        // 許される結果集合:
        //  (a) 失敗が先にロックを掛けた → 成功側は行ロック取得後の再確認で AUTH_003（発行 0 件）
        //  (b) 成功が先に行ロックを取った → 成功側は発行 1 件（失敗側は AUTH_074。成功で失敗計数がリセットされ、ロックは掛からない）
        assertThat(outcomes.get(0)).as("失敗側は常に拒否される（AUTH_074 か、ロック確定後の AUTH_003）")
                .isIn("AUTH_074", "AUTH_003");
        assertThat(outcomes.get(1)).as("成功側は発行されるか、ロック確定後の AUTH_003 のどちらか").isIn("OK", "AUTH_003");
        int issued = "OK".equals(outcomes.get(1)) ? 1 : 0;
        assertThat(refreshTokenCount(u.userId())).as("発行数は成功した要求数と一致する").isEqualTo(issued);
        if (issued == 0) {
            // ロック確定後も OAuth ログイン自体は通りチャレンジが発行されるが、検証は全拒否される
            assertThat(errorCode(validateFrom(oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub),
                    "TOTP", currentTotp(u.totpSecret()))))
                    .as("ロック確定後は新しいセッションでも全拒否").isEqualTo("AUTH_003");
            assertThat(loginSuccess(u.userId())).as("拒否された側は LOGIN_SUCCESS を出さない").isEmpty();
        } else {
            assertThat(loginSuccess(u.userId())).as("発行 1 件なら LOGIN_SUCCESS も 1 件").hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-16 要素ごとの完走・設定変化
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-16: EMAIL 方式ユーザーは OAuth 由来セッションでメールOTP を受け取り完走できる（method=OAUTH_GOOGLE）")
    void EMAIL方式で完走() throws Exception {
        MfaUser u = emailUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));
        String code = sendLoginOtpAndGetCode(u, session);

        MvcResult r = validateFrom(session, "EMAIL", code);

        assertThat(r.getResponse().getStatus()).as("AC-16 EMAIL: %s", body(r)).isEqualTo(200);
        assertThat(loginSuccess(u.userId())).singleElement()
                .satisfies(e -> assertThat(e.getMethod()).isEqualTo("OAUTH_GOOGLE"));
    }

    @Test
    @DisplayName("AC-16: バックアップコードでも OAuth 由来セッションを完走できる（method=OAUTH_LINE）")
    void バックアップコードで完走() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.LINE, link(u.userId(), OAuthAccountEntity.OAuthProvider.LINE));

        MvcResult r = validateFrom(session, "BACKUP_CODE", u.backupCodes().get(0));

        assertThat(r.getResponse().getStatus()).as("AC-16 BACKUP_CODE: %s", body(r)).isEqualTo(200);
        assertThat(loginSuccess(u.userId())).singleElement()
                .satisfies(e -> assertThat(e.getMethod()).isEqualTo("OAUTH_LINE"));
    }

    @Test
    @DisplayName("AC-16: OAuth 由来 challenge 発行後に 2FA が無効化されたら AUTH_073 で拒否し発行しない")
    void challenge後に2FA無効化なら拒否() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.APPLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.APPLE));
        jdbcTemplate.update("DELETE FROM two_factor_auth WHERE user_id = ?", u.userId());

        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(errorCode(r)).isEqualTo("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).isZero();
        assertThat(loginSuccess(u.userId())).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-6 / AC-7 パスワード経路・旧形式
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-6/判断①: パスワード＋2FA の LOGIN_SUCCESS にも sessionHash=SHA-256(jti)・検証要求の ip/UA・method=PASSWORD、refresh_tokens にも ip/UA")
    void パスワード経由2FAのLoginSuccess() throws Exception {
        MfaUser u = totpUser();
        oauthUserIds.add(u.userId());
        String session = loginForMfaSession(u);

        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("AC-6: %s", body(r)).isEqualTo(200);
        Map<String, Object> rt = latestRefreshRow(u.userId());
        assertThat(loginSuccess(u.userId())).singleElement().satisfies(e -> {
            assertThat(e.getMethod()).isEqualTo("PASSWORD");
            assertThat(e.getIpAddress()).isEqualTo(ip);
            assertThat(e.getUserAgent()).isEqualTo(UA_VALIDATE);
            assertThat(e.getSessionHash()).isEqualTo(SessionHashUtil.hash((String) rt.get("jti")));
        });
        assertThat(rt.get("ip_address")).isEqualTo(ip);
        assertThat(rt.get("user_agent")).isEqualTo(UA_VALIDATE);
    }

    @Test
    @DisplayName("AC-7 改/AC-12: 方式キーの無い旧形式セッションでも完了でき method=PASSWORD、成功後の再利用は AUTH_073")
    void 旧形式セッションの互換と単回消費() throws Exception {
        MfaUser u = totpUser();
        oauthUserIds.add(u.userId());
        String raw = "legacy-" + UUID.randomUUID();
        valkey.rawSet(MFA_SESSION_KEY_PREFIX + authTokenService.hashToken(raw), String.valueOf(u.userId()), Duration.ofMinutes(5));

        MvcResult ok = validateFrom(raw, "BACKUP_CODE", u.backupCodes().get(0));
        MvcResult reuse = validateFrom(raw, "BACKUP_CODE", u.backupCodes().get(1));

        assertThat(ok.getResponse().getStatus()).as("AC-7 旧形式で完了: %s", body(ok)).isEqualTo(200);
        assertThat(loginSuccess(u.userId())).singleElement()
                .satisfies(e -> assertThat(e.getMethod()).as("AC-7 方式キー欠落→PASSWORD").isEqualTo("PASSWORD"));
        assertThat(errorCode(reuse)).as("AC-12 旧形式でも再利用不可").isEqualTo("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-12 単回消費（OAuth 由来）
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12: OAuth 由来 challenge は成功後の再利用を AUTH_073 で拒否し、発行・LOGIN_SUCCESS は各 1 件")
    void OAuth由来challengeの再利用拒否() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));

        MvcResult ok = validateFrom(session, "BACKUP_CODE", u.backupCodes().get(0));
        MvcResult reuse = validateFrom(session, "BACKUP_CODE", u.backupCodes().get(1));

        assertThat(ok.getResponse().getStatus()).as("前提: %s", body(ok)).isEqualTo(200);
        assertThat(errorCode(reuse)).isEqualTo("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).isEqualTo(1);
        assertThat(loginSuccess(u.userId())).hasSize(1);
    }

    @Test
    @DisplayName("AC-12: 同一 OAuth 由来 challenge への同時成功でも発行・LOGIN_SUCCESS(監査) は各 1 件")
    void OAuth由来challengeの同時成功は1件() throws Exception {
        MfaUser u = totpUser();
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE));
        String code0 = u.backupCodes().get(0);
        String code1 = u.backupCodes().get(1);

        int successes = runConcurrently(
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, code0, ip, UA_VALIDATE),
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, code1, ip, UA_VALIDATE));

        assertThat(successes).as("AC-12 成功は 1 件").isEqualTo(1);
        assertThat(refreshTokenCount(u.userId())).isEqualTo(1);
        awaitLoginAuditRow(u.userId());
        TimeUnit.SECONDS.sleep(1);
        assertThat(loginAuditCount(u.userId())).as("AC-12 LOGIN_SUCCESS 監査は 1 件").isEqualTo(1);
        Map<String, Object> audit = awaitLoginAuditRow(u.userId());
        assertThat(objectMapper.readTree((String) audit.get("metadata")).path("method").asText()).isEqualTo("OAUTH_GOOGLE");
    }

    /** 方式キーの無い旧形式の MFA セッション（値は userId のみ）を Valkey に直接置く。 */
    private String legacySession(MfaUser u) {
        oauthUserIds.add(u.userId());
        String raw = "legacy-" + UUID.randomUUID();
        valkey.rawSet(MFA_SESSION_KEY_PREFIX + authTokenService.hashToken(raw), String.valueOf(u.userId()), Duration.ofMinutes(5));
        return raw;
    }

    @Test
    @DisplayName("AC-12/AC-7 改: 方式キーの無い旧形式セッションへの同時成功でも発行・LOGIN_SUCCESS(監査) は各 1 件（method=PASSWORD）")
    void 旧形式セッションの同時成功は1件() throws Exception {
        MfaUser u = totpUser();
        String session = legacySession(u);
        String code0 = u.backupCodes().get(0);
        String code1 = u.backupCodes().get(1);

        int successes = runConcurrently(
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, code0, ip, UA_VALIDATE),
                () -> auth2faService.validateMfaLogin(session, MfaVerificationMethod.BACKUP_CODE, code1, ip, UA_VALIDATE));

        assertThat(successes).as("AC-12 旧形式でも成功は 1 件").isEqualTo(1);
        assertThat(refreshTokenCount(u.userId())).isEqualTo(1);
        awaitLoginAuditRow(u.userId());
        TimeUnit.SECONDS.sleep(1);
        assertThat(loginAuditCount(u.userId())).as("AC-12 LOGIN_SUCCESS 監査は 1 件").isEqualTo(1);
        Map<String, Object> audit = awaitLoginAuditRow(u.userId());
        assertThat(objectMapper.readTree((String) audit.get("metadata")).path("method").asText())
                .as("AC-7 方式キー欠落→PASSWORD").isEqualTo("PASSWORD");
    }

    @Test
    @DisplayName("AC-12/AC-7 改: 旧形式セッションも 5 回目の失敗で AUTH_075・以後は正しいコードでも AUTH_073（再利用拒否・発行なし）")
    void 旧形式セッションの上限失敗後は再利用拒否() throws Exception {
        MfaUser u = totpUser();
        String session = legacySession(u);
        for (int i = 0; i < 4; i++) {
            assertThat(errorCode(validateFrom(session, "TOTP", wrongTotp(u.totpSecret())))).isEqualTo("AUTH_074");
        }

        MvcResult fifth = validateFrom(session, "TOTP", wrongTotp(u.totpSecret()));
        MvcResult after = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(errorCode(fifth)).isEqualTo("AUTH_075");
        assertThat(errorCode(after)).as("AC-12 上限失敗後の再利用は拒否").isEqualTo("AUTH_073");
        assertThat(refreshTokenCount(u.userId())).isZero();
        assertThat(loginSuccess(u.userId())).isEmpty();
    }

    /** 2 本を同時開始し、各結果（OK か業務エラーコード）を渡した順で返す。 */
    private List<String> runConcurrentlyOutcomes(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.call();
                        return "OK";
                    } catch (BusinessException ex) {
                        return ex.getErrorCode().getCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private int runConcurrently(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                    ok++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).as("敗者は業務例外で拒否される").isInstanceOf(BusinessException.class);
                }
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-15 発行統一の副作用
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-15: 2FA 経由の発行でも端末指紋・有効期限・last_login_at・新端末通知がパスワード直経路と同じ")
    void 二FA経由発行の副作用() throws Exception {
        MfaUser u = totpUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);
        // 既知でない端末からのログインにするため、別端末の既存セッションを 1 件置く
        refreshTokenRepository.save(RefreshTokenEntity.builder()
                .userId(u.userId()).tokenHash("it-" + UUID.randomUUID()).jti(UUID.randomUUID().toString())
                .rememberMe(false).ipAddress("192.0.2.200").userAgent("other-device").deviceFingerprint("other-fp")
                .expiresAt(LocalDateTime.now().plusDays(7)).build());
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);

        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("前提: %s", body(r)).isEqualTo(200);
        Map<String, Object> rt = latestRefreshRow(u.userId());
        assertThat(rt.get("device_fingerprint")).as("AC-15 端末指紋").isNotNull();
        LocalDateTime createdAt = (LocalDateTime) rt.get("created_at");
        LocalDateTime expiresAt = (LocalDateTime) rt.get("expires_at");
        assertThat(Duration.between(createdAt, expiresAt).getSeconds())
                .as("AC-15 有効期限はパスワード直経路と同じ設定値")
                .isBetween(authTokenService.getRefreshTokenExpirationSeconds() - 60,
                        authTokenService.getRefreshTokenExpirationSeconds() + 60);
        assertThat(lastLoginAt(u.userId())).as("AC-15 last_login_at 更新").isNotNull();
        assertThat(events.stream(NewDeviceLoginEvent.class).filter(e -> e.getUserId() == u.userId()))
                .as("AC-15 新端末通知").hasSize(1);
        assertThat(loginSuccess(u.userId())).as("AC-15 LOGIN_SUCCESS 重複なし").hasSize(1);
    }

    @Test
    @DisplayName("AC-15: 2FA 経由の発行でもセッション上限（10）を適用する")
    void 二FA経由発行のセッション上限() throws Exception {
        MfaUser u = totpUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);
        for (int i = 0; i < 10; i++) {
            refreshTokenRepository.save(RefreshTokenEntity.builder()
                    .userId(u.userId()).tokenHash("it-" + UUID.randomUUID()).jti(UUID.randomUUID().toString())
                    .rememberMe(false).ipAddress(ip).userAgent(UA_VALIDATE).deviceFingerprint("fp-" + i)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build());
        }
        String session = oauthMfaSession(OAuthAccountEntity.OAuthProvider.GOOGLE, sub);

        MvcResult r = validateFrom(session, "TOTP", currentTotp(u.totpSecret()));

        assertThat(r.getResponse().getStatus()).as("前提: %s", body(r)).isEqualTo(200);
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL AND expires_at > NOW()",
                Integer.class, u.userId());
        assertThat(active).as("AC-15 アクティブセッションは上限 10 以下").isLessThanOrEqualTo(10);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-17 FE/BE の code 受け渡し契約
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-17: BE は認可コードをクエリ（?code=）で受ける契約を維持する（JSON body だけでは 400）。FE はクエリ送信に合わせる")
    void codeはクエリで受ける契約() throws Exception {
        long userId = plainUser();
        String sub = link(userId, OAuthAccountEntity.OAuthProvider.GOOGLE);

        MvcResult bodyOnly = mockMvc.perform(withIp(post(OAUTH_PATH + "google").servletPath(OAUTH_PATH + "google"), ip)
                        .header("User-Agent", UA_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", FakeOAuthProviders.code(sub, "x@example.com")))))
                .andReturn();
        MvcResult query = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub, "x@example.com");

        assertThat(bodyOnly.getResponse().getStatus()).as("AC-17 body のみは 400: %s", body(bodyOnly)).isEqualTo(400);
        assertThat(query.getResponse().getStatus()).as("AC-17 クエリは 200: %s", body(query)).isEqualTo(200);
    }
}
