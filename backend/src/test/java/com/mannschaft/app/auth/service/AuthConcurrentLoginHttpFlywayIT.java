package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP0514追加control。実Tomcat/Security/MVC/HTTP/Cookieと実Flyway/実FKを同時に通す。
 * RANDOM_PORTはHTTP応答・Set-Cookieをcommit後に検証するため必要で、共有MOCK基底を継承しない。
 * 専用TC以外に接続せず、外部Redisだけstubする。元のService版RED3/probeは変更しない。
 * SQL gateは対象userの実SQL成功後だけ保持し、認証・Cookie・SQL結果・TXを置換しない。
 * 観測不能/権限/timeoutはFixtureExceptionであり、契約違反のREDと区別する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.docker.compose.enabled=false", "app.datasource.replica.enabled=false",
        "mannschaft.email.simulate=true", "mannschaft.cookie.secure=false",
        "spring.datasource.hikari.data-source-properties.useCursorFetch=false",
        "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=0"
})
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
@Import(AuthConcurrentLoginHttpFlywayIT.HttpObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("CMP0514 実HTTP loginのread-viewと通常refresh競合")
class AuthConcurrentLoginHttpFlywayIT {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(45);
    private static final String USER_AGENT = "CMP0514 owned HTTP control";
    private static final String PASSWORD = UUID.randomUUID() + "Aa1!";
    private static final String ROLE_HEADER = "X-CMP0514-Test-Role";
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private static final AtomicReference<Gate> ACTIVE = new AtomicReference<>();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_cmp0514_http").withUsername("test").withPassword("test")
            .withReuse(false).withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1", "--performance-schema=ON",
                    "--innodb-lock-wait-timeout=40", "--default-time-zone=+00:00");

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AuthTokenService tokenService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    @Autowired private javax.sql.DataSource dataSource;
    @MockitoBean private StringRedisTemplate redis;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void verifyOwnedFixtureAndObservationPermissions() throws SQLException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new FixtureException("fixtureを包むtest-managed TXは禁止");
        }
        assertThat(dataSource).isInstanceOf(ObservedDataSource.class);
        assertThat(((ObservedDataSource) dataSource).pool.getJdbcUrl()).isEqualTo(MYSQL.getJdbcUrl());
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(values);
        given(values.increment(anyString())).willReturn(1L);
        given(redis.hasKey(anyString())).willReturn(false);
        try (Connection connection = ownConnection()) {
            require(count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1") > 0,
                    "実Flyway履歴がない");
            require(count(connection, "SELECT COUNT(*) FROM information_schema.key_column_usage "
                    + "WHERE constraint_schema=DATABASE() AND table_name='refresh_tokens' "
                    + "AND constraint_name='fk_refresh_tokens_user' AND column_name='user_id' "
                    + "AND referenced_table_name='users' AND referenced_column_name='id'") == 1,
                    "実users FKがない");
        }
        // case1では実際のP_S待機観測が必須。新GRANTや共有DB接続で補わない。
        try (Connection connection = observationConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.executeQuery("SELECT * FROM performance_schema.data_lock_waits LIMIT 0").close();
            statement.executeQuery("SELECT * FROM performance_schema.data_locks LIMIT 0").close();
            statement.executeQuery("SELECT THREAD_ID,PROCESSLIST_ID FROM performance_schema.threads LIMIT 0").close();
        } catch (SQLException error) {
            throw observationFailure(error);
        }
    }

    @Test
    void 既存9と近接2HTTPloginはusersX後のFK待機を経ても新2件を含む10件になる() throws Exception {
        Fixture fixture = committedFixture(9);
        Gate gate = new Gate(fixture.userId, Mode.USER_UPDATE);
        require(ACTIVE.compareAndSet(null, gate), "別gateが残存");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpOutcome>> workers = new ArrayList<>();
        try {
            Future<HttpOutcome> first = pool.submit(() -> loginHttp(fixture, "FIRST"));
            workers.add(first);
            awaitLatch(gate.reached, "第一users UPDATE成功後のX保持に到達しない");
            require(gate.actualTransaction.get(), "users X保持が実TX外");
            Future<HttpOutcome> second = pool.submit(() -> loginHttp(fixture, "SECOND"));
            workers.add(second);
            try {
                await().atMost(WAIT).until(() -> gate.secondInsertEntered.get() && parentFkWait(gate));
            } catch (org.awaitility.core.ConditionTimeoutException error) {
                throw new FixtureException("第二HTTPの同user FK待機を観測できない");
            }
            gate.release.countDown();
            HttpOutcome a = completed(first), b = completed(second);
            assertHttpContract(a);
            assertHttpContract(b);
            assertThat(a.refreshHash).as("HTTP2件が別tokenを返す").isNotEqualTo(b.refreshHash);
            try (Connection connection = ownConnection()) {
                assertThat(activeCount(connection, fixture.userId)).as("RCで待機後のcommit済みtokenも数える").isEqualTo(10);
                assertLive(connection, fixture.userId, a.refreshHash);
                assertLive(connection, fixture.userId, b.refreshHash);
                assertThat(revokedExisting(connection, fixture)).containsExactly(fixture.existingIds.getFirst());
            }
        } finally {
            finish(gate, pool, workers);
        }
    }

    @Test
    void 既存10のlogin一覧取得後に通常HTTPrefreshが最古AをBへ置換しても上限と後継を守る() throws Exception {
        Fixture fixture = committedFixture(10);
        Gate gate = new Gate(fixture.userId, Mode.ACTIVE_RESULT);
        require(ACTIVE.compareAndSet(null, gate), "別gateが残存");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpOutcome>> workers = new ArrayList<>();
        try {
            Future<HttpOutcome> login = pool.submit(() -> loginHttp(fixture, "LOGIN"));
            workers.add(login);
            awaitLatch(gate.reached, "loginの物理active結果取得に到達しない");
            require(gate.actualTransaction.get(), "active結果保持が実TX外");
            Future<HttpOutcome> refresh = pool.submit(() -> refreshHttp(fixture));
            workers.add(refresh);
            HttpOutcome replacement = completed(refresh);
            assertHttpContract(replacement);
            // HTTP応答後のfresh JDBCで、解放前にA→Bが確定commitしたことを前提確認する。
            try (Connection connection = ownConnection()) {
                require(successor(connection, fixture.existingIds.getFirst()).equals(replacement.refreshHash),
                        "解放前に通常refreshのA→Bがcommitしていない");
                require(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE id=? AND revoked_at IS NOT NULL",
                        fixture.existingIds.getFirst()) == 1, "解放前にAが失効していない");
                require(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND token_hash=? "
                        + "AND revoked_at IS NULL", fixture.userId, replacement.refreshHash) == 1,
                        "解放前にBが生存していない");
            }
            gate.release.countDown();
            HttpOutcome issued = completed(login);
            assertHttpContract(issued);
            try (Connection connection = ownConnection()) {
                org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
                    try {
                        softly.assertThat(activeCount(connection, fixture.userId)).as("通常refresh競合後のactive上限").isEqualTo(10);
                        softly.assertThat(successor(connection, fixture.existingIds.getFirst()))
                                .as("loginのstale Aで後継pointerを消さない").isEqualTo(replacement.refreshHash);
                        softly.assertThat(revokedExisting(connection, fixture))
                                .as("refresh済みAに加え、次に古い1件だけを失効")
                                .containsExactly(fixture.existingIds.get(0), fixture.existingIds.get(1));
                        softly.assertThat(isLive(connection, fixture.userId, replacement.refreshHash)).as("B生存").isTrue();
                        softly.assertThat(isLive(connection, fixture.userId, issued.refreshHash)).as("login新token生存").isTrue();
                    } catch (SQLException error) {
                        throw observationFailure(error);
                    }
                });
            }
        } finally {
            finish(gate, pool, workers);
        }
    }

    private Fixture committedFixture(int existing) throws SQLException {
        String email = "cmp0514-http-" + UUID.randomUUID() + "@example.invalid";
        String currentHash = encoder.encode(PASSWORD);
        require(!encoder.upgradeEncoding(currentHash), "current encoderのhashがupgrade対象");
        String rawOldest = UUID.randomUUID().toString();
        Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> {
            UserEntity user = users.saveAndFlush(UserEntity.builder().email(email).passwordHash(currentHash)
                    .lastName("HTTP").firstName("試験").displayName("CMP0514 HTTP")
                    .isSearchable(false).locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE).lastLoginAt(LocalDateTime.now().minusDays(1)).build());
            List<Long> ids = new ArrayList<>();
            for (int index = 0; index < existing; index++) {
                String raw = index == 0 ? rawOldest : UUID.randomUUID().toString();
                ids.add(tokens.saveAndFlush(RefreshTokenEntity.builder().userId(user.getId())
                        .tokenHash(tokenService.hashToken(raw)).jti(UUID.randomUUID().toString()).rememberMe(false)
                        .ipAddress("192.0.2.51").deviceFingerprint(tokenService.hashToken(USER_AGENT)).userAgent(USER_AGENT)
                        .lastUsedAt(LocalDateTime.now().minusDays(2).plusMinutes(index))
                        .expiresAt(LocalDateTime.now().plusDays(7)).build()).getId());
            }
            return new Fixture(user.getId(), email, ids, rawOldest);
        });
        require(fixture != null, "fixture TXの結果がない");
        try (Connection connection = ownConnection()) {
            require(activeCount(connection, fixture.userId) == existing, "fixtureがcommitされていない");
            require(count(connection, "SELECT COUNT(*) FROM two_factor_auth WHERE user_id=?", fixture.userId) == 0,
                    "fixtureは2FAoffでない");
        }
        return fixture;
    }

    private HttpOutcome loginHttp(Fixture fixture, String role) {
        try {
            String body = mapper.writeValueAsString(Map.of("email", fixture.email, "password", PASSWORD, "rememberMe", false));
            return send(HttpRequest.newBuilder(uri("/login")).header(ROLE_HEADER, role)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        } catch (IOException error) {
            throw new FixtureException("HTTP request JSON生成失敗");
        }
    }

    private HttpOutcome refreshHttp(Fixture fixture) {
        return send(HttpRequest.newBuilder(uri("/refresh")).header(ROLE_HEADER, "REFRESH")
                .header("Cookie", "refresh_token=" + fixture.rawOldest).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1/auth" + path); }

    private HttpOutcome send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = client.send(request.timeout(HTTP_TIMEOUT).header("User-Agent", USER_AGENT).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new HttpOutcome(response.statusCode(), "", "", List.of());
            }
            JsonNode data = mapper.readTree(response.body()).path("data");
            String access = data.path("accessToken").asText(), refresh = data.path("refreshToken").asText();
            assertThat(!access.isBlank() && !refresh.isBlank()).as("HTTP bodyに両tokenがある").isTrue();
            List<CookieInfo> cookies = response.headers().allValues("Set-Cookie").stream().map(this::cookieInfo).toList();
            return new HttpOutcome(200, tokenService.hashToken(access), tokenService.hashToken(refresh), cookies);
        } catch (IOException error) {
            throw new FixtureException("HTTP接続/応答JSON失敗（秘密のbodyは出力しない）");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FixtureException("HTTP worker中断");
        }
    }

    private CookieInfo cookieInfo(String header) {
        String[] pieces = header.split(";");
        int separator = pieces[0].indexOf('=');
        require(separator > 0, "不正なSet-Cookie形式");
        Map<String, String> attributes = new HashMap<>();
        for (int index = 1; index < pieces.length; index++) {
            String[] pair = pieces[index].trim().split("=", 2);
            attributes.put(pair[0].toLowerCase(Locale.ROOT), pair.length == 1 ? "" : pair[1]);
        }
        return new CookieInfo(pieces[0].substring(0, separator), tokenService.hashToken(pieces[0].substring(separator + 1)),
                attributes.get("path"), attributes.get("samesite"), attributes.containsKey("httponly"),
                attributes.containsKey("secure"), Long.parseLong(attributes.getOrDefault("max-age", "-1")));
    }

    private void assertHttpContract(HttpOutcome outcome) {
        assertThat(outcome.status).as("実HTTP login/refreshが成功する").isEqualTo(200);
        assertThat(outcome.cookies).extracting(CookieInfo::name).containsExactlyInAnyOrder("access_token", "refresh_token");
        for (CookieInfo cookie : outcome.cookies) {
            assertThat(cookie.httpOnly).isTrue();
            assertThat(cookie.secure).as("owned loopback HTTPの既存Cookie設定").isFalse();
            assertThat(cookie.path).isEqualTo("/");
            assertThat(cookie.sameSite).isEqualTo("Strict");
            boolean access = cookie.name.equals("access_token");
            assertThat(cookie.valueHash).isEqualTo(access ? outcome.accessHash : outcome.refreshHash);
            assertThat(cookie.maxAge).isEqualTo(access ? tokenService.getAccessTokenExpirationSeconds()
                    : tokenService.getRefreshTokenExpirationSeconds());
        }
    }

    private static int activeCount(Connection connection, long userId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL "
                + "AND expires_at>UTC_TIMESTAMP()", userId);
    }

    private static boolean isLive(Connection connection, long userId, String hash) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND token_hash=? "
                + "AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()", userId, hash) == 1;
    }

    private static void assertLive(Connection connection, long userId, String hash) throws SQLException {
        assertThat(isLive(connection, userId, hash)).as("HTTPで発行したtokenが確定commit後も生存").isTrue();
    }

    private static List<Long> revokedExisting(Connection connection, Fixture fixture) throws SQLException {
        List<Long> revoked = new ArrayList<>();
        for (Long id : fixture.existingIds) {
            if (count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND id=? AND revoked_at IS NOT NULL",
                    fixture.userId, id) == 1) revoked.add(id);
        }
        return revoked;
    }

    private static String successor(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT replaced_by_token_hash FROM refresh_tokens WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet row = statement.executeQuery()) {
                require(row.next(), "Aが存在しない");
                return row.getString(1) == null ? "" : row.getString(1);
            }
        }
    }

    private static int count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (ResultSet row = statement.executeQuery()) {
                require(row.next(), "COUNT行がない");
                return row.getInt(1);
            }
        }
    }

    private static boolean parentFkWait(Gate gate) {
        Long first = gate.connections.get("FIRST"), second = gate.connections.get("SECOND");
        if (first == null || second == null) return false;
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE "
                + "JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND b.ENGINE=w.ENGINE "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID=r.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID=b.THREAD_ID "
                + "WHERE rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=? "
                + "AND r.OBJECT_SCHEMA=DATABASE() AND r.OBJECT_NAME='users' AND r.INDEX_NAME='PRIMARY' "
                + "AND r.LOCK_DATA=? AND r.LOCK_STATUS='WAITING' AND r.LOCK_MODE LIKE 'S%' "
                + "AND b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME='users' AND b.INDEX_NAME='PRIMARY' "
                + "AND b.LOCK_DATA=? AND b.LOCK_STATUS='GRANTED' AND b.LOCK_MODE LIKE 'X%'";
        try (Connection connection = observationConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(2);
            statement.setLong(1, second); statement.setLong(2, first);
            statement.setString(3, Long.toString(gate.userId)); statement.setString(4, Long.toString(gate.userId));
            try (ResultSet row = statement.executeQuery()) {
                require(row.next(), "P_S COUNT行がない");
                return row.getInt(1) > 0;
            }
        } catch (SQLException error) {
            throw observationFailure(error);
        }
    }

    private static Connection ownConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection observationConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "root"); properties.setProperty("password", MYSQL.getPassword());
        properties.setProperty("connectTimeout", "2000"); properties.setProperty("socketTimeout", "3000");
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), properties);
    }

    private static FixtureException observationFailure(SQLException error) {
        return new FixtureException("ownTC SQL観測失敗 SQLState=" + error.getSQLState() + " errorCode=" + error.getErrorCode());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new FixtureException(message);
    }

    private static void awaitLatch(CountDownLatch latch, String reason) {
        try {
            require(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), reason);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new FixtureException(reason);
        }
    }

    private static HttpOutcome completed(Future<HttpOutcome> worker) {
        try {
            return worker.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof AssertionError assertion) throw assertion;
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new FixtureException("HTTP worker不明失敗");
        } catch (TimeoutException error) {
            throw new FixtureException("HTTP worker完了timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new FixtureException("HTTP worker回収中断");
        }
    }

    private static void finish(Gate gate, ExecutorService pool, List<Future<HttpOutcome>> workers) {
        gate.release.countDown();
        for (Future<HttpOutcome> worker : workers) if (!worker.isDone()) worker.cancel(true);
        pool.shutdownNow();
        ACTIVE.compareAndSet(gate, null);
        try {
            require(pool.awaitTermination(10, TimeUnit.SECONDS), "所有HTTP executorの回収timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new FixtureException("所有HTTP executor回収中断");
        }
    }

    private enum Mode { USER_UPDATE, ACTIVE_RESULT }
    private static final class Gate {
        final long userId;
        final Mode mode;
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean(), actualTransaction = new AtomicBoolean();
        final AtomicBoolean secondInsertEntered = new AtomicBoolean();
        final Map<String, Long> connections = new ConcurrentHashMap<>();
        Gate(long userId, Mode mode) { this.userId = userId; this.mode = mode; }
        void hold() {
            if (held.compareAndSet(false, true)) {
                actualTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                reached.countDown();
                try {
                    require(release.await(60, TimeUnit.SECONDS), "所有SQL gate解放timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new FixtureException("所有SQL gate保持中断");
                }
            }
        }
    }

    private static final class Fixture {
        final long userId;
        final String email;
        final List<Long> existingIds;
        final String rawOldest;
        Fixture(long userId, String email, List<Long> ids, String rawOldest) {
            this.userId = userId; this.email = email; this.existingIds = List.copyOf(ids); this.rawOldest = rawOldest;
        }
    }
    private record CookieInfo(String name, String valueHash, String path, String sameSite,
                              boolean httpOnly, boolean secure, long maxAge) { }
    private record HttpOutcome(int status, String accessHash, String refreshHash, List<CookieInfo> cookies) { }
    private static final class FixtureException extends RuntimeException {
        FixtureException(String message) { super(message); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HttpObservationConfiguration {
        /** role印だけ。認証・CSRF・Cookie・HTTP statusを変更せず、必ず解除する。 */
        @Bean
        OncePerRequestFilter httpWorkerRole() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    String role = request.getHeader(ROLE_HEADER);
                    if (ACTIVE.get() != null && role != null && List.of("FIRST", "SECOND", "LOGIN", "REFRESH").contains(role)
                            && List.of("/api/v1/auth/login", "/api/v1/auth/refresh").contains(request.getRequestURI())) ROLE.set(role);
                    try { chain.doFilter(request, response); }
                    finally { ROLE.remove(); }
                }
            };
        }

        @Bean
        static BeanPostProcessor ownedHttpSqlObserver() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof HikariDataSource pool && "dataSource".equals(name) ? new ObservedDataSource(pool) : bean;
                }
            };
        }
    }

    /** 対象userのnumeric bindだけを保持する薄い委譲。秘密値/SQL本文は記録・出力しない。 */
    static final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;
        ObservedDataSource(HikariDataSource pool) { super(pool); this.pool = pool; }
        @Override public Connection getConnection() throws SQLException { return observe(pool.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return observe(pool.getConnection(username, password));
        }
        private Connection observe(Connection connection) throws SQLException {
            Gate gate = ACTIVE.get(); String role = ROLE.get();
            if (gate != null && role != null) {
                try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT CONNECTION_ID(),@@foreign_key_checks")) {
                    require(row.next() && row.getInt(2) == 1, "HTTP worker接続の実FK検査が無効");
                    gate.connections.putIfAbsent(role, row.getLong(1));
                }
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = delegate(connection, method, args);
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement
                                && args != null && args[0] instanceof String sql) return observeStatement(statement, sql);
                        return result;
                    });
        }

        private PreparedStatement observeStatement(PreparedStatement statement, String sql) {
            String normalized = sql.replace("`", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            Map<Integer, Long> numeric = new HashMap<>();
            List<Map<Integer, Long>> batches = new ArrayList<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("set") && args != null && args.length > 1 && args[0] instanceof Integer index) {
                            if (args[1] instanceof Number number) numeric.put(index, number.longValue());
                            else numeric.remove(index);
                        } else if (method.getName().equals("clearParameters")) numeric.clear();
                        else if (method.getName().equals("addBatch") && (args == null || args.length == 0)) batches.add(new HashMap<>(numeric));
                        else if (method.getName().equals("clearBatch")) batches.clear();
                        Gate gate = ACTIVE.get(); String role = ROLE.get();
                        boolean execute = method.getName().startsWith("execute");
                        boolean batchExecution = method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch");
                        List<Map<Integer, Long>> bound = batchExecution && !batches.isEmpty() ? List.copyOf(batches) : List.of(numeric);
                        if (execute && gate != null && "SECOND".equals(role) && normalized.startsWith("insert into refresh_tokens")
                                && matchesUser(bound, insertUserParameter(normalized), gate.userId)) gate.secondInsertEntered.set(true);
                        Object result = delegate(statement, method, args);
                        if (batchExecution) batches.clear();
                        if (!execute || gate == null) return result;
                        if (gate.mode == Mode.USER_UPDATE && "FIRST".equals(role) && normalized.startsWith("update users ")
                                && matchesUser(bound, whereParameter(normalized, "id"), gate.userId) && changed(result, statement)) gate.hold();
                        if (gate.mode == Mode.ACTIVE_RESULT && "LOGIN".equals(role) && result instanceof ResultSet
                                && normalized.startsWith("select ") && !normalized.startsWith("select count(")
                                && normalized.contains(" from refresh_tokens ") && normalized.contains("revoked_at is null")
                                && matchesUser(bound, whereParameter(normalized, "user_id"), gate.userId)) {
                            // Connector/Jの通常buffered ResultSetをexecuteQuery成功後・Hibernate読取り前に保持する。
                            // DBがこのSELECTで返した古A集合は固定済み。結果の差替えや再queryは行わない。
                            require(statement.getFetchSize() != Integer.MIN_VALUE, "streaming ResultSetでは古A集合を保持できない");
                            gate.hold();
                        }
                        return result;
                    });
        }

        private static boolean matchesUser(List<Map<Integer, Long>> bound, int parameter, long userId) {
            return parameter > 0 && bound.stream().anyMatch(numeric -> Long.valueOf(userId).equals(numeric.get(parameter)));
        }
        private static int whereParameter(String sql, String field) {
            int where = sql.indexOf(" where ");
            if (where < 0) return -1;
            Matcher match = Pattern.compile("\\b(?:[a-z0-9_]+\\.)?" + field + "\\s*=\\s*\\?").matcher(sql);
            if (!match.find(where)) return -1;
            int question = sql.indexOf('?', match.start());
            return (int) sql.substring(0, question + 1).chars().filter(c -> c == '?').count();
        }
        private static int insertUserParameter(String sql) {
            int columnsStart = sql.indexOf('('), columnsEnd = sql.indexOf(')', columnsStart);
            int values = sql.indexOf("values", columnsEnd), valuesStart = sql.indexOf('(', values);
            if (columnsStart < 0 || columnsEnd < 0 || values < 0 || valuesStart < 0) return -1;
            String[] columns = sql.substring(columnsStart + 1, columnsEnd).split(",");
            String[] entries = sql.substring(valuesStart + 1, sql.indexOf(')', valuesStart)).split(",");
            if (columns.length != entries.length) return -1;
            int parameter = 0;
            for (int index = 0; index < columns.length; index++) {
                if (entries[index].contains("?")) parameter++;
                if (columns[index].trim().equals("user_id")) return entries[index].trim().equals("?") ? parameter : -1;
            }
            return -1;
        }
        private static boolean changed(Object result, PreparedStatement statement) throws SQLException {
            if (result instanceof Number number) return number.longValue() > 0;
            if (result instanceof int[] counts) return java.util.Arrays.stream(counts).anyMatch(n -> n > 0 || n == Statement.SUCCESS_NO_INFO);
            if (result instanceof long[] counts) return java.util.Arrays.stream(counts).anyMatch(n -> n > 0 || n == Statement.SUCCESS_NO_INFO);
            return result instanceof Boolean && statement.getUpdateCount() > 0;
        }
        private static Object delegate(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
        @Override public void close() { pool.close(); }
    }
}
