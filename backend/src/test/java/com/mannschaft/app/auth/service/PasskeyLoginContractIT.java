package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.util.SessionHashUtil;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * パスキーログインの受け入れ試練（AC-4・AC-6〜15）。
 * 実Controller/Security・実Bean・MySQLへ実署名を送り、commit後の永続化まで観測する。
 * チャレンジの一回性は実ValkeyのGETDELを通す。共通基底の外部Redisモックは実接続へ委譲する。
 * テスト全体をTransactionalで包まず、DBの途中失敗とCookie不発行を検査する。
 * 署名不一致拒否など既存の正しい契約はcharacterizationとして初回からgreen、是正対象はredを実測済み。
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PasskeyLoginContractIT extends AbstractMySqlIntegrationTest {

    private static final String BEGIN = "/api/v1/auth/webauthn/login/begin";
    private static final String COMPLETE = "/api/v1/auth/webauthn/login/complete";
    private static final String USER_AGENT = "Mozilla/5.0 PasskeyContract";
    private static final String CHALLENGE_PREFIX = "mannschaft:auth:webauthn_challenge:login:";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static final AtomicInteger NEXT_IP = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private WebAuthnCredentialRepository credentials;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private TwoFactorAuthRepository twoFactors;
    @Autowired private AuthTokenService tokenService;
    @Autowired private AuthWebAuthnService webAuthnService;
    @Autowired private Clock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEvents events;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Value("${mannschaft.webauthn.rp-id:mannschaft.app}") private String rpId;
    @Value("${mannschaft.webauthn.origin:https://mannschaft.app}") private String origin;

    private StringRedisTemplate realRedis;
    private final List<Long> ownedUsers = new ArrayList<>();
    private UserEntity user;
    private WebAuthnCredentialEntity credential;
    private PasskeyAssertionFixture authenticator;
    private String ip;
    private String failureConstraintTable;

    @BeforeEach
    void 準備() throws Exception {
        if (!VALKEY.isRunning()) {
            VALKEY.start();
            connectionFactory = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
            connectionFactory.afterPropertiesSet();
            connectionFactory.start();
        }
        realRedis = new StringRedisTemplate(connectionFactory);
        realRedis.afterPropertiesSet();
        reset(redisTemplate);
        given(redisTemplate.opsForValue()).willReturn(realRedis.opsForValue());
        given(redisTemplate.hasKey(anyString())).willAnswer(i -> realRedis.hasKey((String) i.getArgument(0)));
        given(redisTemplate.delete(anyString())).willAnswer(i -> realRedis.delete((String) i.getArgument(0)));
        given(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .willAnswer(i -> realRedis.expire((String) i.getArgument(0), (Long) i.getArgument(1), (TimeUnit) i.getArgument(2)));
        given(redisTemplate.getExpire(anyString(), any(TimeUnit.class)))
                .willAnswer(i -> realRedis.getExpire((String) i.getArgument(0), (TimeUnit) i.getArgument(1)));
        int ipNumber = NEXT_IP.incrementAndGet();
        ip = "198.18." + ipNumber / 256 + "." + ipNumber % 256;
        user = createUser(UserEntity.UserStatus.ACTIVE);
        authenticator = new PasskeyAssertionFixture();
        credential = saveCredential(user, authenticator, 0);
    }

    @AfterEach
    void 後始末() {
        if (failureConstraintTable != null) {
            jdbc.execute("ALTER TABLE " + failureConstraintTable + " DROP CHECK passkey_login_failure");
            failureConstraintTable = null;
        }
        for (Long userId : ownedUsers) {
            credentials.deleteAll(credentials.findByUserId(userId));
            refreshTokens.deleteAll(refreshTokens.findByUserIdAndRevokedAtIsNull(userId));
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            twoFactors.findByUserId(userId).ifPresent(twoFactors::delete);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
            realRedis.delete(CHALLENGE_PREFIX + userId);
            realRedis.delete("mannschaft:auth:account_lock:" + userId);
        }
        ownedUsers.clear();
    }

    @AfterAll
    static void 接続を閉じる() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @ParameterizedTest
    // 0x1d = UP/UV/BE/BS: バックアップ済み同期パスキーの0固定カウンタも受理する。
    @CsvSource({"0,0,5", "0,1,5", "7,8,5", "4294967294,4294967295,5", "0,0,29"})
    void AC7_署名済みカウンタの有効な遷移が成功して永続化する(long stored, long signed, int flags) throws Exception {
        setStoredCounter(stored);
        Clock fixedClock = Clock.fixed(Instant.parse("2031-12-31T15:30:00Z"), ZoneOffset.UTC);
        ReflectionTestUtils.setField(webAuthnService, "clock", fixedClock);
        try {
            String challenge = begin(user);
            MvcResult result = complete(assertion(challenge, flags, signed, signed));

            assertSuccess(result);
            WebAuthnCredentialEntity reloaded = credentials.findById(credential.getId()).orElseThrow();
            assertThat(reloaded.getSignCount()).isEqualTo(signed);
            // UTCの瞬間を既存列のJST壁時計へ変換し、年・日境界を越えてもずれない。
            assertThat(reloaded.getLastUsedAt()).isEqualTo(LocalDateTime.of(2032, 1, 1, 0, 30));
            assertThat(credentials.findByUserId(user.getId())).hasSize(1);
        } finally {
            ReflectionTestUtils.setField(webAuthnService, "clock", clock);
        }
    }

    @Test
    void AC7_申告カウンタを信用せず署名対象のカウンタだけ保存する() throws Exception {
        MvcResult result = complete(assertion(begin(user), 5, 1, Long.MAX_VALUE));

        assertSuccess(result);
        assertThat(credentials.findById(credential.getId()).orElseThrow().getSignCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 6, 7})
    void AC7_正値カウンタの非増加を拒否する(long signed) throws Exception {
        setStoredCounter(7);
        MvcResult result = complete(assertion(begin(user), 5, signed, Long.MAX_VALUE));
        assertRejected(result);
        assertThat(credentials.findById(credential.getId()).orElseThrow().getSignCount()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void AC8_UPまたはUVの無い実署名を拒否する(int flags) throws Exception {
        assertRejected(complete(assertion(begin(user), flags, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "origin", "rpId", "challenge", "base64"})
    void AC8_偽署名と署名対象の不一致を拒否する(String altered) throws Exception {
        String challenge = begin(user);
        WebAuthnLoginCompleteRequest request = authenticator.assertion(
                altered.equals("challenge") ? Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[32]) : challenge,
                altered.equals("origin") ? "https://attacker.example" : origin,
                altered.equals("rpId") ? "attacker.example" : rpId, 5, 1, 1);
        if (altered.equals("signature")) {
            byte[] invalid = Base64.getUrlDecoder().decode(request.getSignature());
            invalid[invalid.length - 1] ^= 1;
            request = new WebAuthnLoginCompleteRequest(request.getCredentialId(), request.getAuthenticatorData(),
                    request.getClientDataJson(), Base64.getUrlEncoder().withoutPadding().encodeToString(invalid), 1);
        } else if (altered.equals("base64")) {
            request = new WebAuthnLoginCompleteRequest(request.getCredentialId(), "%%%",
                    request.getClientDataJson(), request.getSignature(), 1);
        }
        assertRejected(complete(request));
    }

    @Test
    void AC6_未登録メールと資格情報ゼロを拒否してトークンを発行しない() throws Exception {
        assertThat(beginResult("absent-" + UUID.randomUUID() + "@example.com").getResponse().getStatus())
                .isBetween(400, 499);
        credentials.delete(credential);
        assertThat(beginResult(user.getEmail()).getResponse().getStatus()).isBetween(400, 499);
        assertThat(refreshTokens.countByUserId(user.getId())).isZero();
    }

    @Test
    void AC9_チャレンジは5分期限で不在と失効を拒否する() throws Exception {
        String challenge = begin(user);
        Long ttl = realRedis.getExpire(CHALLENGE_PREFIX + user.getId());
        assertThat(ttl).isBetween(295L, 300L);
        realRedis.expire(CHALLENGE_PREFIX + user.getId(), Duration.ZERO);
        assertRejected(complete(assertion(challenge, 5, 1, 1)));
    }

    @Test
    void AC9_チャレンジを再利用できない() throws Exception {
        WebAuthnLoginCompleteRequest request = assertion(begin(user), 5, 0, 0);
        assertSuccess(complete(request));
        MvcResult second = complete(request);
        assertThat(second.getResponse().getStatus()).isBetween(400, 499);
        assertThat(second.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(refreshTokens.countByUserId(user.getId())).isEqualTo(1);
    }

    @Test
    void AC9_同一チャレンジを並行完了しても成功は最大一回() throws Exception {
        WebAuthnLoginCompleteRequest request = assertion(begin(user), 5, 0, 0);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return complete(request); });
            var second = executor.submit(() -> { start.await(); return complete(request); });
            start.countDown();
            List<MvcResult> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 200).count()).isEqualTo(1);
            assertThat(refreshTokens.countByUserId(user.getId())).isEqualTo(1);
        }
    }

    @Test
    void AC10_他ユーザーのチャレンジで認証できない() throws Exception {
        UserEntity other = createUser(UserEntity.UserStatus.ACTIVE);
        saveCredential(other, new PasskeyAssertionFixture(), 0);
        begin(user);
        String otherChallenge = begin(other);
        assertRejected(complete(assertion(otherChallenge, 5, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_VERIFICATION", "FROZEN", "deleted", "locked", "credentialDeleted"})
    void AC10_開始後に利用不可となったアカウントや資格情報を拒否する(String state) throws Exception {
        String challenge = begin(user);
        if (state.equals("deleted")) {
            user.requestDeletion();
            users.save(user);
        } else if (state.equals("locked")) {
            realRedis.opsForValue().set("mannschaft:auth:account_lock:" + user.getId(), "1", Duration.ofMinutes(30));
        } else if (state.equals("credentialDeleted")) {
            credentials.delete(credential);
        } else {
            user.changeStatus(UserEntity.UserStatus.valueOf(state));
            users.save(user);
        }
        assertRejected(complete(assertion(challenge, 5, 1, 1)));
        if (!state.equals("credentialDeleted")) {
            assertThat(credentials.findById(credential.getId()).orElseThrow().getSignCount()).isZero();
        }
    }

    @Test
    void AC11_ARCHIVEDは復帰しTOTP有効でもUV済みパスキーで認証する() throws Exception {
        user.archive();
        users.save(user);
        twoFactors.save(TwoFactorAuthEntity.builder().userId(user.getId())
                .totpSecret("unused-for-passkey").backupCodes("[]").isEnabled(true).build());

        assertSuccess(complete(assertion(begin(user), 5, 1, 1)));
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
    }

    @Test
    void AC11_保護者同意待ちは制限付きトークンを発行する() throws Exception {
        user.changeStatus(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        users.save(user);
        MvcResult result = complete(assertion(begin(user), 5, 1, 1));
        assertSuccess(result);
        String accessToken = data(result).path("accessToken").asText();
        assertThat(tokenService.parseAccessToken(accessToken).get("ppc", Boolean.class)).isTrue();
        MvcResult protectedResult = mockMvc.perform(get("/api/v1/auth/sessions")
                .header("Authorization", "Bearer " + accessToken)).andReturn();
        assertThat(protectedResult.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void AC4_管理者ロールを実DBからトークンへ反映する() throws Exception {
        RoleEntity admin = roles.findByName("SYSTEM_ADMIN").orElseGet(() -> roles.save(RoleEntity.builder()
                .name("SYSTEM_ADMIN").displayName("システム管理者").priority(100).isSystem(true).build()));
        userRoles.save(UserRoleEntity.builder().userId(user.getId()).roleId(admin.getId()).build());

        MvcResult result = complete(assertion(begin(user), 5, 1, 1));
        assertSuccess(result);
        assertThat(tokenService.parseAccessToken(data(result).path("accessToken").asText())
                .get("roles", List.class)).contains("SYSTEM_ADMIN");
    }

    @Test
    void AC4_AC11_ARCHIVED管理者は復帰後の管理者ロールでトークンを発行する() throws Exception {
        RoleEntity admin = roles.findByName("SYSTEM_ADMIN").orElseGet(() -> roles.save(RoleEntity.builder()
                .name("SYSTEM_ADMIN").displayName("システム管理者").priority(100).isSystem(true).build()));
        userRoles.save(UserRoleEntity.builder().userId(user.getId()).roleId(admin.getId()).build());
        user.archive();
        users.save(user);

        MvcResult result = complete(assertion(begin(user), 5, 1, 1));

        assertSuccess(result);
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
        assertThat(tokenService.parseAccessToken(data(result).path("accessToken").asText())
                .get("roles", List.class)).contains("SYSTEM_ADMIN");
    }

    @Test
    void AC14_資格情報数が増えても一括検索一回で開始する() throws Exception {
        for (int index = 0; index < 20; index++) {
            saveCredential(user, new PasskeyAssertionFixture(), 0);
        }
        SqlIntentCounter.reset();

        MvcResult result = beginResult(user.getEmail());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(result).path("allowCredentials").size()).isEqualTo(21);
        assertThat(SqlIntentCounter.intentCount("webauthn_credentials")).isEqualTo(1);
    }

    @Test
    void AC12_Cookieと設定期限とセッションIDと監査引数と最終ログインを保つ() throws Exception {
        MvcResult result = complete(assertion(begin(user), 5, 1, 1));
        assertSuccess(result);
        JsonNode response = data(result);
        Long sessionId = response.path("sessionId").longValue();
        RefreshTokenEntity token = refreshTokens.findById(sessionId).orElseThrow();
        assertThat(token.getUserId()).isEqualTo(user.getId());
        assertThat(token.getTokenHash()).isEqualTo(tokenService.hashToken(response.path("refreshToken").asText()));
        assertThat(token.getDeviceFingerprint()).isEqualTo(tokenService.hashToken(USER_AGENT));
        assertThat(response.path("expiresIn").longValue()).isEqualTo(tokenService.getAccessTokenExpirationSeconds());
        assertThat(token.getExpiresAt()).isBetween(
                LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenExpirationSeconds() - 60),
                LocalDateTime.now().plusSeconds(tokenService.getRefreshTokenExpirationSeconds() + 60));
        assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).hasSize(2).allSatisfy(cookie -> {
            assertThat(cookie).contains("HttpOnly", "Path=/", "SameSite=Strict");
        });
        assertThat(cookies.stream().filter(c -> c.startsWith("access_token=")).findFirst().orElseThrow())
                .contains("Max-Age=" + tokenService.getAccessTokenExpirationSeconds());
        assertThat(cookies.stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow())
                .contains("Max-Age=" + tokenService.getRefreshTokenExpirationSeconds());
        List<LoginSuccessEvent> logins = events.stream(LoginSuccessEvent.class)
                .filter(e -> e.getUserId().equals(user.getId())).toList();
        assertThat(logins).hasSize(1);
        assertThat(logins.getFirst().getIpAddress()).isEqualTo(ip);
        assertThat(logins.getFirst().getUserAgent()).isEqualTo(USER_AGENT);
        assertThat(logins.getFirst().getMethod()).isEqualTo("WEBAUTHN");
        assertThat(logins.getFirst().getSessionHash()).isEqualTo(SessionHashUtil.hash(token.getJti()));
    }

    @Test
    void AC12_セッション上限と新端末検出を既存ログインと共有する() throws Exception {
        for (int index = 0; index < 10; index++) {
            refreshTokens.save(RefreshTokenEntity.builder().userId(user.getId())
                    .tokenHash(tokenService.hashToken("old-" + UUID.randomUUID())).jti(UUID.randomUUID().toString())
                    .rememberMe(false).deviceFingerprint("old-device").ipAddress("192.0.2.1")
                    .expiresAt(LocalDateTime.now().plusDays(7)).build());
        }
        assertSuccess(complete(assertion(begin(user), 5, 1, 1)));
        assertThat(refreshTokens.countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(user.getId(), LocalDateTime.now()))
                .isLessThanOrEqualTo(10);
        assertThat(events.stream(NewDeviceLoginEvent.class)
                .filter(e -> e.getUserId().equals(user.getId())).count()).isEqualTo(1);
    }

    @Test
    void AC13_実トークン生成が失敗しても資格情報を巻き戻して再開始できる() throws Exception {
        String challenge = begin(user);
        long originalExpiration = tokenService.getAccessTokenExpirationSeconds();
        try {
            // 不正な設定値を注入し、実JWT発行のInstant計算を失敗させる。業務Beanのモックは使わない。
            ReflectionTestUtils.setField(tokenService, "accessTokenExpirationSeconds", Long.MAX_VALUE);
            MvcResult failed = complete(assertion(challenge, 5, 1, 1));
            assertThat(failed.getResponse().getStatus()).isEqualTo(500);
            assertThat(failed.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(refreshTokens.countByUserId(user.getId())).isZero();
            assertThat(credentials.findById(credential.getId()).orElseThrow().getSignCount()).isZero();
        } finally {
            ReflectionTestUtils.setField(tokenService, "accessTokenExpirationSeconds", originalExpiration);
        }
        assertSuccess(complete(assertion(begin(user), 5, 1, 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh_tokens", "users"})
    void AC13_DB途中失敗は資格情報とRTを巻き戻しCookie無しで再開始できる(String failingTable) throws Exception {
        String challenge = begin(user);
        // この試験ユーザーの行だけを拒否する自然なCHECK制約で、実DBの書込み・commitを失敗させる。
        // 共有DBの権限・global設定を変更せず、終了時は制約を必ず削除する。
        String condition = failingTable.equals("users")
                ? "email <> '" + user.getEmail() + "' OR last_login_at IS NULL"
                : "user_id <> " + user.getId();
        jdbc.execute("ALTER TABLE " + failingTable + " ADD CONSTRAINT passkey_login_failure CHECK (" + condition + ")");
        failureConstraintTable = failingTable;
        MvcResult failed = complete(assertion(challenge, 5, 1, 1));
        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        assertThat(failed.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(refreshTokens.countByUserId(user.getId())).isZero();
        assertThat(credentials.findById(credential.getId()).orElseThrow().getSignCount()).isZero();
        jdbc.execute("ALTER TABLE " + failingTable + " DROP CHECK passkey_login_failure");
        failureConstraintTable = null;
        assertSuccess(complete(assertion(begin(user), 5, 1, 1)));
    }

    @Test
    void AC14_公開ログイン開始と完了に既存10回毎分レート制限を適用する() throws Exception {
        for (int index = 0; index < 10; index++) {
            assertThat(beginResult(user.getEmail()).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(beginResult(user.getEmail()).getResponse().getStatus()).isEqualTo(429);
        WebAuthnLoginCompleteRequest unknown = new WebAuthnLoginCompleteRequest("unknown", "eA", "eA", "eA", 0);
        for (int index = 0; index < 10; index++) {
            assertThat(complete(unknown).getResponse().getStatus()).isBetween(400, 499);
        }
        assertThat(complete(unknown).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void AC15_パスワード成功とTOTP要求のCookie契約を維持する() throws Exception {
        user.updatePasswordHash(passwordEncoder.encode("Password1!"));
        users.save(user);
        LoginRequest request = new LoginRequest(user.getEmail(), "Password1!", false, null);
        MvcResult password = mockMvc.perform(post("/api/v1/auth/login").with(r -> {
            r.setRemoteAddr(ip); return r;
        }).header("User-Agent", USER_AGENT).contentType("application/json")
                .content(objectMapper.writeValueAsString(request))).andReturn();
        assertThat(password.getResponse().getStatus()).isEqualTo(200);
        assertThat(password.getResponse().getHeaders("Set-Cookie")).hasSize(2);
        assertThat(events.stream(LoginSuccessEvent.class).filter(e -> e.getUserId().equals(user.getId()))
                .findFirst().orElseThrow().getMethod()).isEqualTo("PASSWORD");
        twoFactors.save(TwoFactorAuthEntity.builder().userId(user.getId())
                .totpSecret("unused").backupCodes("[]").isEnabled(true).build());
        MvcResult mfa = mockMvc.perform(post("/api/v1/auth/login").with(r -> {
            r.setRemoteAddr(ip); return r;
        }).contentType("application/json").content(objectMapper.writeValueAsString(request))).andReturn();
        assertThat(mfa.getResponse().getStatus()).isEqualTo(200);
        assertThat(mfa.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(data(mfa).path("mfaSessionToken").asText()).isNotBlank();
    }

    private UserEntity createUser(UserEntity.UserStatus status) {
        UserEntity created = users.save(UserEntity.builder().email("passkey-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("太郎").displayName("パスキー試練")
                .isSearchable(false).locale("en").timezone("Asia/Tokyo").status(status).build());
        ownedUsers.add(created.getId());
        return created;
    }

    private WebAuthnCredentialEntity saveCredential(UserEntity owner, PasskeyAssertionFixture fixture, long count) {
        return credentials.save(WebAuthnCredentialEntity.builder().userId(owner.getId())
                .credentialId(fixture.credentialId()).publicKey(fixture.publicKey()).signCount(count)
                .deviceName("試験用認証器").build());
    }

    private void setStoredCounter(long count) {
        // 既存builderでfixtureの初期カウンタだけ変える（本番更新パターンを検査対象へ混ぜない）。
        credentials.delete(credential);
        credential = saveCredential(user, authenticator, count);
    }

    private String begin(UserEntity owner) throws Exception {
        MvcResult result = beginResult(owner.getEmail());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return data(result).path("challenge").asText();
    }

    private MvcResult beginResult(String email) throws Exception {
        return mockMvc.perform(post(BEGIN).param("email", email).with(r -> {
            r.setRemoteAddr(ip); return r;
        })).andReturn();
    }

    private WebAuthnLoginCompleteRequest assertion(String challenge, int flags, long signed, long reported)
            throws Exception {
        return authenticator.assertion(challenge, origin, rpId, flags, signed, reported);
    }

    private MvcResult complete(WebAuthnLoginCompleteRequest request) throws Exception {
        return mockMvc.perform(post(COMPLETE).with(r -> { r.setRemoteAddr(ip); return r; })
                .header("User-Agent", USER_AGENT).contentType("application/json")
                .content(objectMapper.writeValueAsString(request))).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private void assertSuccess(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(data(result).path("accessToken").asText()).isNotBlank();
        assertThat(data(result).path("refreshToken").asText()).isNotBlank();
    }

    private void assertRejected(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(refreshTokens.countByUserId(user.getId())).isZero();
    }
}
