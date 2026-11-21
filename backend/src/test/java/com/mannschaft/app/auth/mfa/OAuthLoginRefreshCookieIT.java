package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261121-0438 試練: OAuth ログイン（2FA 無効の即発行経路）が refresh 用 Set-Cookie を出さず、
 * リロード（{@code POST /api/v1/auth/refresh}）で session_expired に落ちる欠陥の受け入れテスト。
 *
 * <p><b>構成</b>: {@link OAuthMfaLoginIT} と同じ。実 Controller → 実 Service → 実 MySQL（Testcontainers）・
 * 実 Security フィルタ（{@link MfaItSupport}）。外部境界のみ代役: Valkey は {@link MfaValkeyFake}、
 * OAuth プロバイダ（Google / LINE / Apple）は JDK 標準の {@link HttpServer} で偽装する。
 * 偽プロバイダは認可コードに {@code <sub>~<email>} を埋めて返す。{@code fail~} で始まるコードは token
 * エンドポイントが 400 を返す（プロバイダ認証失敗）。</p>
 *
 * <p><b>Cookie 属性の基準</b>: パスワードログイン（{@code AuthLoginController#login} → {@code AuthTokenCookies}）の
 * Set-Cookie を同じ context で採り、値と {@code Expires} を除いた属性集合が一致することを確かめる。
 * 加えて HttpOnly・Path=/・SameSite=Strict・Domain 無し・Max-Age=AuthTokenService の秒数を個別に確かめる。</p>
 *
 * <p>本テストは {@code @Transactional} にしない（refresh のローテーションを確定状態で観測するため）。</p>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261121-0438 OAuth 即発行経路の refresh Cookie（HTTP 境界）")
class OAuthLoginRefreshCookieIT extends MfaEmailOtpItSupport {

    private static final String OAUTH_PATH = "/api/v1/auth/oauth/";
    private static final String CONFIRM_PATH = "/api/v1/auth/oauth/link/confirm";
    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final String UA = "OAuthRefreshCookieIT/1.0";
    private static final String FAIL_PREFIX = "fail~";

    private static final HttpServer FAKE_PROVIDERS = startFakeProviders();

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;
    @Autowired
    private OAuthLinkTokenRepository oauthLinkTokenRepository;

    private final List<Long> oauthUserIds = new CopyOnWriteArrayList<>();

    // ─────────────────────────────────────────────────────────────
    // 偽 OAuth プロバイダ（OAuthMfaLoginIT と同方式）
    // ─────────────────────────────────────────────────────────────

    private static HttpServer startFakeProviders() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/google/token", ex -> {
                String code = codeOf(ex);
                if (code.startsWith(FAIL_PREFIX)) {
                    respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                respond(ex, 200, "{\"access_token\":\"" + code + "\"}");
            });
            server.createContext("/google/userinfo", ex -> {
                String[] subEmail = splitCode(ex.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
                respond(ex, 200, "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1]
                        + "\",\"given_name\":\"Taro\",\"family_name\":\"Yamada\",\"name\":\"oauth-refresh-it\"}");
            });
            server.createContext("/line/token", ex -> respond(ex, 200,
                    "{\"access_token\":\"fake-at\",\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
            server.createContext("/apple/token", ex -> respond(ex, 200,
                    "{\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("偽 OAuth プロバイダの起動に失敗しました", e);
        }
    }

    private static String codeOf(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            if (pair.startsWith("code=")) {
                return URLDecoder.decode(pair.substring("code=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("code が送られていない: " + form);
    }

    private static String[] splitCode(String code) {
        return code.split("~", 2);
    }

    /** 署名なしの偽 id_token を実行時に組み立てる（トークン風リテラルを書かない）。 */
    private static String idToken(String code) {
        String[] subEmail = splitCode(code);
        String payload = "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1] + "\",\"name\":\"oauth-refresh-it\"}";
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @DynamicPropertySource
    static void configureFakeProviders(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + FAKE_PROVIDERS.getAddress().getPort();
        registry.add("mannschaft.google.token-uri", () -> base + "/google/token");
        registry.add("mannschaft.google.userinfo-uri", () -> base + "/google/userinfo");
        registry.add("mannschaft.line.token-uri", () -> base + "/line/token");
        registry.add("mannschaft.apple.token-uri", () -> base + "/apple/token");
    }

    @AfterAll
    static void stopFakeProviders() {
        FAKE_PROVIDERS.stop(0);
    }

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

    private String link(long userId, OAuthAccountEntity.OAuthProvider provider) {
        oauthUserIds.add(userId);
        String sub = "it-sub-" + UUID.randomUUID();
        oauthAccountRepository.save(OAuthAccountEntity.builder()
                .userId(userId).provider(provider).providerUserId(sub)
                .providerEmail("oauth-refresh-" + UUID.randomUUID() + "@example.com").build());
        return sub;
    }

    private MvcResult oauthLogin(OAuthAccountEntity.OAuthProvider provider, String code) throws Exception {
        String path = OAUTH_PATH + provider.name().toLowerCase(Locale.ROOT);
        return mockMvc.perform(withIp(post(path).servletPath(path), ip)
                        .param("code", code)
                        .header("User-Agent", UA))
                .andReturn();
    }

    private MvcResult confirm(String rawToken) throws Exception {
        return mockMvc.perform(withIp(post(CONFIRM_PATH).servletPath(CONFIRM_PATH), ip)
                        .param("token", rawToken)
                        .header("User-Agent", UA))
                .andReturn();
    }

    private MvcResult refreshWith(String rawRefreshCookie) throws Exception {
        return mockMvc.perform(withIp(post(REFRESH_PATH).servletPath(REFRESH_PATH), ip)
                        .header("User-Agent", UA)
                        .cookie(new Cookie("refresh_token", rawRefreshCookie)))
                .andReturn();
    }

    private MvcResult logoutWith(String accessCookie, String refreshCookie) throws Exception {
        return mockMvc.perform(withIp(post(LOGOUT_PATH).servletPath(LOGOUT_PATH), ip)
                        .header("User-Agent", UA)
                        .cookie(new Cookie("access_token", accessCookie), new Cookie("refresh_token", refreshCookie)))
                .andReturn();
    }

    /** 連携確認トークン（メールで届く平文）を発行済みの状態を作る。 */
    private String linkTokenFor(long userId, String sub, LocalDateTime expiresAt) {
        oauthUserIds.add(userId);
        String raw = "link-" + UUID.randomUUID();
        oauthLinkTokenRepository.save(OAuthLinkTokenEntity.builder()
                .userId(userId)
                .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                .providerUserId(sub)
                .providerEmail("oauth-refresh-" + UUID.randomUUID() + "@example.com")
                .tokenHash(authTokenService.hashToken(raw))
                .expiresAt(expiresAt)
                .build());
        return raw;
    }

    private String emailOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    // ─────────────────────────────────────────────────────────────
    // Set-Cookie 観測
    // ─────────────────────────────────────────────────────────────

    private static List<String> setCookies(MvcResult r) {
        return r.getResponse().getHeaders("Set-Cookie");
    }

    /** 指定名の Set-Cookie ヘッダ（無ければ null）。 */
    private static String setCookie(MvcResult r, String name) {
        return setCookies(r).stream().filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String cookieValue(String header) {
        String first = header.split(";", 2)[0];
        return first.substring(first.indexOf('=') + 1);
    }

    /** 値と Expires（発行時刻依存）を除いた属性集合（小文字化・並び順非依存）。 */
    private static List<String> attributesWithoutValue(String header) {
        return Arrays.stream(header.split(";"))
                .skip(1)
                .map(String::trim)
                .filter(a -> !a.toLowerCase(Locale.ROOT).startsWith("expires="))
                .map(a -> a.toLowerCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 基準: 同じ context のパスワードログイン（AuthTokenCookies 経由）の Set-Cookie。 */
    private Map<String, String> passwordLoginCookies() throws Exception {
        long refUser = plainUser();
        oauthUserIds.add(refUser);
        MvcResult ref = login(emailOf(refUser));
        assertThat(ref.getResponse().getStatus()).as("基準のパスワードログイン: %s", body(ref)).isEqualTo(200);
        String access = setCookie(ref, "access_token");
        String refresh = setCookie(ref, "refresh_token");
        assertThat(access).as("基準 access_token Cookie").isNotNull();
        assertThat(refresh).as("基準 refresh_token Cookie").isNotNull();
        return Map.of("access_token", access, "refresh_token", refresh);
    }

    /**
     * AC-1〜3 共通: access_token と refresh_token の 2 つの Set-Cookie が AuthTokenCookies と同じ属性で付き、
     * 本文の accessToken/refreshToken も残り、Cookie 値と本文値が一致する。
     */
    private void assertIssuedCookies(String ac, MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("%s 200: %s", ac, body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        String bodyAccess = data.path("accessToken").asText("");
        String bodyRefresh = data.path("refreshToken").asText("");
        assertThat(bodyAccess).as("%s 本文 accessToken（モバイル互換）: %s", ac, body(r)).isNotBlank();
        assertThat(bodyRefresh).as("%s 本文 refreshToken（モバイル互換）", ac).isNotBlank();

        String access = setCookie(r, "access_token");
        String refresh = setCookie(r, "refresh_token");
        assertThat(access).as("%s access_token の Set-Cookie: %s", ac, setCookies(r)).isNotNull();
        assertThat(refresh).as("%s refresh_token の Set-Cookie: %s", ac, setCookies(r)).isNotNull();
        assertThat(cookieValue(access)).as("%s access Cookie 値＝本文", ac).isEqualTo(bodyAccess);
        assertThat(cookieValue(refresh)).as("%s refresh Cookie 値＝本文", ac).isEqualTo(bodyRefresh);

        assertCookieAttributes(ac, access, authTokenService.getAccessTokenExpirationSeconds());
        assertCookieAttributes(ac, refresh, authTokenService.getRefreshTokenExpirationSeconds());

        Map<String, String> ref = passwordLoginCookies();
        assertThat(attributesWithoutValue(access)).as("%s access 属性はパスワードログインと同じ", ac)
                .isEqualTo(attributesWithoutValue(ref.get("access_token")));
        assertThat(attributesWithoutValue(refresh)).as("%s refresh 属性はパスワードログインと同じ", ac)
                .isEqualTo(attributesWithoutValue(ref.get("refresh_token")));
    }

    private static void assertCookieAttributes(String ac, String header, long maxAgeSeconds) {
        List<String> attrs = attributesWithoutValue(header);
        assertThat(attrs).as("%s HttpOnly: %s", ac, header).contains("httponly");
        assertThat(attrs).as("%s Path=/: %s", ac, header).contains("path=/");
        assertThat(attrs).as("%s SameSite=Strict: %s", ac, header).contains("samesite=strict");
        assertThat(attrs).as("%s Max-Age: %s", ac, header).contains("max-age=" + maxAgeSeconds);
        assertThat(attrs).as("%s Domain 無し: %s", ac, header).noneMatch(a -> a.startsWith("domain="));
    }

    /** AC-5 共通: Set-Cookie が 1 つも付かず、本文にトークンが無い。 */
    private void assertNoTokens(String ac, MvcResult r) throws Exception {
        assertThat(setCookies(r)).as("%s Set-Cookie なし: %s", ac, body(r)).isEmpty();
        JsonNode data = json(r).path("data");
        assertThat(data.has("accessToken")).as("%s 本文 accessToken なし: %s", ac, body(r)).isFalse();
        assertThat(data.has("refreshToken")).as("%s 本文 refreshToken なし: %s", ac, body(r)).isFalse();
    }

    private Map<String, Object> refreshRowByHash(String tokenHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_id, revoked_at FROM refresh_tokens WHERE token_hash = ?", tokenHash);
        assertThat(rows).as("token_hash=%s の refresh_tokens 行", tokenHash).hasSize(1);
        return rows.get(0);
    }

    /** 2FA 無効・既存連携の OAuth ログインを行い、即発行された応答を返す。 */
    private MvcResult plainOAuthLogin(long userId) throws Exception {
        String sub = link(userId, OAuthAccountEntity.OAuthProvider.GOOGLE);
        return oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub + "~x-" + UUID.randomUUID() + "@example.com");
    }

    // ═════════════════════════════════════════════════════════════
    // AC-1 既存連携・2FA 無効の OAuth ログイン
    // ═════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GOOGLE", "LINE", "APPLE"})
    @DisplayName("AC-1: 既存連携・2FA 無効の OAuth ログインは access_token/refresh_token の 2 つの Set-Cookie（AuthTokenCookies と同属性）を付け、本文のトークンも残す")
    void 既存連携ログインは2つのCookieを発行する(String providerName) throws Exception {
        OAuthAccountEntity.OAuthProvider provider = OAuthAccountEntity.OAuthProvider.valueOf(providerName);
        long userId = plainUser();
        String sub = link(userId, provider);

        MvcResult r = oauthLogin(provider, sub + "~x-" + UUID.randomUUID() + "@example.com");

        assertIssuedCookies("AC-1", r);
        assertThat(json(r).path("data").path("mfaRequired").asBoolean(false)).isFalse();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-2 OAuth 新規ユーザー登録経路
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2: OAuth 新規ユーザー登録経路（2FA 無し）でも 2 つの Set-Cookie と本文のトークン")
    void 新規ユーザー登録経路もCookieを発行する() throws Exception {
        String email = "oauth-refresh-new-" + UUID.randomUUID() + "@example.com";
        String sub = "it-sub-" + UUID.randomUUID();

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub + "~" + email);

        List<Long> created = jdbcTemplate.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
        oauthUserIds.addAll(created);
        assertThat(created).as("AC-2 前提: 新規ユーザーが作られる: %s", body(r)).hasSize(1);
        assertIssuedCookies("AC-2", r);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-3 /link/confirm 成功（2FA 無効）
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3: 2FA 無効ユーザーの /oauth/link/confirm 成功でも 2 つの Set-Cookie と本文のトークン")
    void 連携確認成功もCookieを発行する() throws Exception {
        long userId = plainUser();
        String raw = linkTokenFor(userId, "it-sub-" + UUID.randomUUID(), LocalDateTime.now().plusHours(1));

        MvcResult r = confirm(raw);

        assertIssuedCookies("AC-3", r);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-4 refresh Cookie だけでリロード相当の /refresh が通る
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4: OAuth ログインで得た refresh_token Cookie だけで /refresh が 200・Cookie 再発行、Cookie 値の SHA-256 が refresh_tokens 行と対応しローテーションされる")
    void refreshCookieだけでリロードが通る() throws Exception {
        long userId = plainUser();
        MvcResult login = plainOAuthLogin(userId);
        String refreshHeader = setCookie(login, "refresh_token");
        assertThat(refreshHeader).as("AC-4 前提: OAuth ログインの refresh_token Cookie: %s", setCookies(login)).isNotNull();
        String oldRaw = cookieValue(refreshHeader);
        Map<String, Object> oldRow = refreshRowByHash(authTokenService.hashToken(oldRaw));
        assertThat(((Number) oldRow.get("user_id")).longValue()).as("AC-4 Cookie 値のハッシュは本人の行").isEqualTo(userId);
        assertThat(oldRow.get("revoked_at")).isNull();

        MvcResult r = refreshWith(oldRaw);

        assertThat(r.getResponse().getStatus()).as("AC-4 /refresh 200: %s", body(r)).isEqualTo(200);
        String newAccess = setCookie(r, "access_token");
        String newRefresh = setCookie(r, "refresh_token");
        assertThat(newAccess).as("AC-4 access_token 再発行: %s", setCookies(r)).isNotNull();
        assertThat(newRefresh).as("AC-4 refresh_token 再発行: %s", setCookies(r)).isNotNull();
        assertThat(cookieValue(newAccess)).isNotBlank();
        String newRaw = cookieValue(newRefresh);
        assertThat(newRaw).as("AC-4 ローテーションで新しい値").isNotBlank().isNotEqualTo(oldRaw);
        Map<String, Object> newRow = refreshRowByHash(authTokenService.hashToken(newRaw));
        assertThat(((Number) newRow.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(newRow.get("revoked_at")).as("AC-4 新しい行は有効").isNull();
        assertThat(refreshRowByHash(authTokenService.hashToken(oldRaw)).get("revoked_at"))
                .as("AC-4 旧行は失効済み").isNotNull();
    }

    // ═════════════════════════════════════════════════════════════
    // AC-5 Cookie もトークンも出さない応答
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: MFA 要求（TOTP）の応答には Set-Cookie もトークンも無い")
    void MFA要求TOTPはCookieなし() throws Exception {
        MfaUser u = totpUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub + "~" + u.email());

        assertThat(json(r).path("data").path("mfaRequired").asBoolean()).as("AC-5 前提: MFA 要求: %s", body(r)).isTrue();
        assertNoTokens("AC-5(TOTP)", r);
    }

    @Test
    @DisplayName("AC-5: MFA 要求（メール2FA）の応答には Set-Cookie もトークンも無い")
    void MFA要求EMAILはCookieなし() throws Exception {
        MfaUser u = emailUser();
        String sub = link(u.userId(), OAuthAccountEntity.OAuthProvider.GOOGLE);

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, sub + "~" + u.email());

        JsonNode data = json(r).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("AC-5 前提: MFA 要求: %s", body(r)).isTrue();
        assertThat(data.path("primaryMethod").asText()).isEqualTo("EMAIL");
        assertNoTokens("AC-5(EMAIL)", r);
    }

    @Test
    @DisplayName("AC-5: 2FA 有効ユーザーの /link/confirm（MFA 要求）には Set-Cookie もトークンも無い")
    void 連携確認のMFA要求はCookieなし() throws Exception {
        MfaUser u = totpUser();
        String raw = linkTokenFor(u.userId(), "it-sub-" + UUID.randomUUID(), LocalDateTime.now().plusHours(1));

        MvcResult r = confirm(raw);

        assertThat(json(r).path("data").path("mfaRequired").asBoolean()).as("AC-5 前提: MFA 要求: %s", body(r)).isTrue();
        assertNoTokens("AC-5(confirm MFA)", r);
    }

    @Test
    @DisplayName("AC-5: 連携競合（メール一致の既存ユーザー・未連携）の応答には Set-Cookie もトークンも無い")
    void 連携競合はCookieなし() throws Exception {
        long userId = plainUser();
        oauthUserIds.add(userId);

        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE,
                "it-sub-" + UUID.randomUUID() + "~" + emailOf(userId));

        assertThat(r.getResponse().getStatus()).as("AC-5 前提: %s", body(r)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth_link_tokens WHERE user_id = ?", Integer.class, userId))
                .as("AC-5 前提: 連携確認トークンが発行される（競合経路）").isEqualTo(1);
        assertNoTokens("AC-5(conflict)", r);
        assertThat(refreshTokenCount(userId)).isZero();
    }

    @Test
    @DisplayName("AC-5: 無効な連携トークンでの /link/confirm には Set-Cookie もトークンも無い")
    void 無効な連携トークンはCookieなし() throws Exception {
        MvcResult r = confirm("link-" + UUID.randomUUID());

        assertThat(errorCode(r)).as("AC-5 前提: %s", body(r)).isEqualTo("AUTH_031");
        assertNoTokens("AC-5(invalid link)", r);
    }

    @Test
    @DisplayName("AC-5: 期限切れの連携トークンでの /link/confirm には Set-Cookie もトークンも無い")
    void 期限切れ連携トークンはCookieなし() throws Exception {
        long userId = plainUser();
        String raw = linkTokenFor(userId, "it-sub-" + UUID.randomUUID(), LocalDateTime.now().minusMinutes(1));

        MvcResult r = confirm(raw);

        assertThat(errorCode(r)).as("AC-5 前提: %s", body(r)).isEqualTo("AUTH_031");
        assertNoTokens("AC-5(expired link)", r);
        assertThat(refreshTokenCount(userId)).isZero();
    }

    @Test
    @DisplayName("AC-5: 使用済みの連携トークンでの /link/confirm には Set-Cookie もトークンも無い")
    void 使用済み連携トークンはCookieなし() throws Exception {
        long userId = plainUser();
        String raw = linkTokenFor(userId, "it-sub-" + UUID.randomUUID(), LocalDateTime.now().plusHours(1));
        jdbcTemplate.update("UPDATE oauth_link_tokens SET used_at = ? WHERE token_hash = ?",
                LocalDateTime.now().minusMinutes(1), authTokenService.hashToken(raw));

        MvcResult r = confirm(raw);

        assertThat(errorCode(r)).as("AC-5 前提: %s", body(r)).isEqualTo("AUTH_031");
        assertNoTokens("AC-5(used link)", r);
        assertThat(refreshTokenCount(userId)).isZero();
    }

    @Test
    @DisplayName("AC-5: プロバイダ認証失敗（token エンドポイント 400）の応答には Set-Cookie もトークンも無い")
    void プロバイダ認証失敗はCookieなし() throws Exception {
        MvcResult r = oauthLogin(OAuthAccountEntity.OAuthProvider.GOOGLE, FAIL_PREFIX + UUID.randomUUID());

        assertThat(r.getResponse().getStatus()).as("AC-5 前提: 失敗応答: %s", body(r)).isGreaterThanOrEqualTo(400);
        assertNoTokens("AC-5(provider failure)", r);
    }

    // ═════════════════════════════════════════════════════════════
    // AC-6 OAuth ログイン後の logout
    // ═════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-6: OAuth ログイン後の logout は両 Cookie を Max-Age=0 で消去し、旧 refresh Cookie での /refresh は 401")
    void OAuthログイン後のlogout() throws Exception {
        long userId = plainUser();
        MvcResult login = plainOAuthLogin(userId);
        String accessHeader = setCookie(login, "access_token");
        String refreshHeader = setCookie(login, "refresh_token");
        assertThat(accessHeader).as("AC-6 前提: access_token Cookie: %s", setCookies(login)).isNotNull();
        assertThat(refreshHeader).as("AC-6 前提: refresh_token Cookie: %s", setCookies(login)).isNotNull();
        String oldRefresh = cookieValue(refreshHeader);

        MvcResult out = logoutWith(cookieValue(accessHeader), oldRefresh);

        assertThat(out.getResponse().getStatus()).as("AC-6 logout 200: %s", body(out)).isEqualTo(200);
        String clearedAccess = setCookie(out, "access_token");
        String clearedRefresh = setCookie(out, "refresh_token");
        assertThat(clearedAccess).as("AC-6 access_token 消去: %s", setCookies(out)).isNotNull();
        assertThat(clearedRefresh).as("AC-6 refresh_token 消去: %s", setCookies(out)).isNotNull();
        assertThat(attributesWithoutValue(clearedAccess)).contains("max-age=0");
        assertThat(attributesWithoutValue(clearedRefresh)).contains("max-age=0");
        assertThat(refreshRowByHash(authTokenService.hashToken(oldRefresh)).get("revoked_at"))
                .as("AC-6 refresh_tokens 行は失効").isNotNull();

        MvcResult reuse = refreshWith(oldRefresh);

        assertThat(reuse.getResponse().getStatus()).as("AC-6 旧 refresh Cookie の /refresh は 401: %s", body(reuse))
                .isEqualTo(401);
        assertThat(setCookie(reuse, "refresh_token")).as("AC-6 失効後は Cookie を再発行しない").isNull();
    }
}
