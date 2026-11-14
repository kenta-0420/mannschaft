package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP-260905-0514: 同一ユーザーの近接ログインとセッション上限を実MySQL/Flywayで検査する候補。
 *
 * <p>通常のAbstractMySqlIntegrationTestはddl-auto=createであり、スカラーuserIdの外部キーを
 * 生成しない。本試験は専用コンテナに本番Flywayを適用し、fk_refresh_tokens_userを先に確認する。
 * 内部Service/TXを検査するためTCPサーバーは起動しない。AuthService・Repository・commitは実物である。
 * Redisだけ外部境界としてstubし、メールは既存test profileのsimulate設定を使う。
 * fixtureは明示commitし、workerはテストTXに参加させず、最終状態は新しいJDBC接続から読む。</p>
 *
 * <p>AC1=上限未満の並行2login、AC2=既存9+並行2login、AC3=既存10+1login。
 * 第一INSERTの実成功後だけ第一workerを保持する。第二INSERT成功、または同一users主キーの
 * 第二worker→第一workerのX待機をperformance_schemaで確認して解放する。
 * 無条件の二者INSERT barrierは使わない。観測権限不足・timeoutはfixture errorでありREDではない。
 * 旧sourceではSQLState40001/error1213に由来するlogin成功assertの失敗を期待するが未実走である。
 * 本クラスの追加だけではHTTP 500→200の実機確認を代替しない。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.docker.compose.enabled=false",
        "app.datasource.replica.enabled=false",
        "mannschaft.email.simulate=true",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0",
        "logging.level.com.mannschaft.app.auth.service.AuthConcurrentLoginFlywayIT=INFO"
})
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
@Import(AuthConcurrentLoginFlywayIT.JdbcObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("CMP0514 同一ユーザー近接ログイン（実Flyway・実commit）")
class AuthConcurrentLoginFlywayIT {

    private static final Logger log = LoggerFactory.getLogger(AuthConcurrentLoginFlywayIT.class);

    private static final int SESSION_LIMIT = 10;
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(20);
    private static final int OBSERVATION_CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int OBSERVATION_SOCKET_TIMEOUT_MILLIS = 3_000;
    private static final int OBSERVATION_QUERY_TIMEOUT_SECONDS = 2;
    private static final long COMPLETION_SECONDS = 45;
    // V1.001のusers.last_login_atはDATETIME(0)。小数秒の丸め/切捨て両方を許す。
    private static final Duration DB_TIMESTAMP_TOLERANCE = Duration.ofSeconds(1);
    private static final String IP = "192.0.2.51";
    private static final String USER_AGENT = "CMP0514 isolated integration test";
    private static final String PASSWORD = UUID.randomUUID() + "Aa1!";
    private static final ThreadLocal<Integer> WORKER = new ThreadLocal<>();
    private static final AtomicReference<InsertGate> ACTIVE_GATE = new AtomicReference<>();

    /** このクラスだけが所有するDB。既存runtime DBへfallbackしない。Docker不在は起動エラー。 */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_cmp0514_login")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false)
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1", "--performance-schema=ON",
                    "--innodb-lock-wait-timeout=15", "--default-time-zone=+00:00");

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired private AuthService authService;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    /** 外部Valkeyへ接続しない。認証DB保存やサービス本体は差し替えない。 */
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void verifyFixtureAndExternalBoundary() throws SQLException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new FixtureException("test-managed TXがfixtureを包んでいる");
        }
        if (!ZoneId.systemDefault().equals(UserZoneLocalDateTimeParser.SERVER_ZONE)) {
            throw new FixtureException("本番TimeZoneConfigのSERVER_ZONEとJVM既定TZが不一致");
        }
        assertThat(AopUtils.isAopProxy(authService)).as("本番AuthServiceのSpring proxy").isTrue();
        assertThat(dataSource).isInstanceOf(ObservedDataSource.class);
        assertThat(((ObservedDataSource) dataSource).pool.getJdbcUrl())
                .as("本番/既存runtimeへの接続にfallbackしない").isEqualTo(MYSQL.getJdbcUrl());
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.increment(anyString())).willReturn(1L);
        given(redisTemplate.hasKey(anyString())).willReturn(false);

        try (Connection connection = ownConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"))
                    .as("Flywayの実適用履歴").isPositive();
            assertThat(count(connection, "SELECT COUNT(*) FROM information_schema.key_column_usage "
                    + "WHERE constraint_schema=DATABASE() AND table_name='refresh_tokens' "
                    + "AND constraint_name='fk_refresh_tokens_user' AND column_name='user_id' "
                    + "AND referenced_table_name='users' AND referenced_column_name='id'"))
                    .as("IDENTITY INSERTでusersのSロックを取る実FK").isEqualTo(1);
        }
        // rootはこのクラス所有TCだけ。app接続へ権限を足さず、実wait graphを独立接続で読む。
        try (Connection admin = ownObservationConnection(); var statement = admin.createStatement()) {
            statement.setQueryTimeout(OBSERVATION_QUERY_TIMEOUT_SECONDS);
            statement.executeQuery("SELECT * FROM performance_schema.data_lock_waits LIMIT 0").close();
            statement.executeQuery("SELECT * FROM performance_schema.data_locks LIMIT 0").close();
            statement.executeQuery("SELECT THREAD_ID,PROCESSLIST_ID FROM performance_schema.threads LIMIT 0").close();
        } catch (SQLException error) {
            throw fixtureSql("P_S観測権限/設定が不足", error);
        }
    }

    @Test
    void 上限未満の並行2ログインが両方commitして別tokenを永続化する() throws Exception {
        Fixture fixture = committedFixture(0);
        List<LoginOutcome> outcomes = concurrentLogins(fixture);
        assertSuccessful(outcomes, fixture.userId());
        assertCommittedState(fixture, outcomes, 2, 2);
    }

    @Test
    void 既存9と並行2ログインが両成功し古いreadViewでも上限10を超えない() throws Exception {
        Fixture fixture = committedFixture(SESSION_LIMIT - 1);
        List<LoginOutcome> outcomes = concurrentLogins(fixture);
        assertSuccessful(outcomes, fixture.userId());
        assertCommittedState(fixture, outcomes, SESSION_LIMIT + 1, SESSION_LIMIT);
        assertOldestRevoked(fixture, 1);
    }

    @Test
    void 既存10から1ログインすると最古1件だけ失効し新tokenを含む10件が残る() throws Exception {
        Fixture fixture = committedFixture(SESSION_LIMIT);
        LoginOutcome outcome = login(fixture, 1);
        assertSuccessful(List.of(outcome), fixture.userId());
        assertCommittedState(fixture, List.of(outcome), SESSION_LIMIT + 1, SESSION_LIMIT);
        assertOldestRevoked(fixture, 1);
    }

    /** current encodeを使い、透過rehash・復帰・2FAによるusers先更新を排除する。 */
    private Fixture committedFixture(int existingCount) throws SQLException {
        String email = "cmp0514-" + UUID.randomUUID() + "@example.invalid";
        String currentHash = passwordEncoder.encode(PASSWORD);
        assertThat(passwordEncoder.upgradeEncoding(currentHash)).as("現行hashでは透過rehash不要").isFalse();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Fixture fixture = transaction.execute(status -> {
            UserEntity user = userRepository.saveAndFlush(LoginTestFixture.user(email, currentHash));
            List<Long> ids = new ArrayList<>();
            for (int index = 0; index < existingCount; index++) {
                ids.add(refreshTokenRepository.saveAndFlush(LoginTestFixture.token(
                        user.getId(), authTokenService.hashToken(UUID.randomUUID().toString()), index)).getId());
            }
            return new Fixture(user.getId(), email, List.copyOf(ids),
                    user.getLastLoginAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
        });
        if (fixture == null) {
            throw new FixtureException("fixture TXが結果を返さなかった");
        }
        try (Connection connection = ownConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM users WHERE id=?", fixture.userId())).isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM two_factor_auth WHERE user_id=?", fixture.userId())).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=?", fixture.userId()))
                    .isEqualTo(existingCount);
            Instant persisted = readLastLoginInstant(connection, fixture.userId());
            if (persisted == null || Duration.between(fixture.initialLastLogin(), persisted).abs()
                    .compareTo(DB_TIMESTAMP_TOLERANCE) > 0) {
                throw new FixtureException("fixtureのJPA/JDBC UTC時刻mappingが不一致（deadlock REDに数えない）");
            }
        }
        return fixture;
    }

    private List<LoginOutcome> concurrentLogins(Fixture fixture) throws Exception {
        InsertGate gate = new InsertGate();
        if (!ACTIVE_GATE.compareAndSet(null, gate)) {
            throw new FixtureException("別caseのINSERT gateが残存");
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<LoginOutcome>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> login(fixture, 1)));
            awaitLatch(gate.firstInserted, "第一workerの実INSERT未観測");
            futures.add(executor.submit(() -> login(fixture, 2)));
            try {
                await().pollInSameThread().pollInterval(Duration.ofMillis(25)).atMost(OBSERVATION_TIMEOUT)
                        .until(() -> {
                            if (gate.secondInserted.getCount() == 0) {
                                gate.observation = "SECOND_INSERT_COMPLETED";
                                return true;
                            }
                            if (parentWaitObserved(gate, fixture.userId())) {
                                gate.observation = "SECOND_WAITING_FOR_FIRST_USERS_X";
                                return true;
                            }
                            if (futures.get(1).isDone()) {
                                throw new FixtureException("第二workerがINSERT/parent待機観測前に終了");
                            }
                            return false;
                        });
            } catch (ConditionTimeoutException error) {
                throw new FixtureException("第二INSERT/同一users待機を観測できなかった", error);
            } finally {
                gate.releaseFirst.countDown();
            }
            List<LoginOutcome> outcomes = new ArrayList<>();
            for (Future<LoginOutcome> future : futures) {
                outcomes.add(future.get(COMPLETION_SECONDS, TimeUnit.SECONDS));
            }
            log.info("CMP0514 fixtureUserId={} firstConnection={} secondConnection={} observation={}",
                    fixture.userId(), gate.connectionIds.get(1), gate.connectionIds.get(2), gate.observation);
            assertThat(gate.connectionIds).as("別々の物理接続で2TXを通す").hasSize(2);
            assertThat(gate.connectionIds.get(1)).isNotEqualTo(gate.connectionIds.get(2));
            assertThat(gate.firstInsertInTransaction.get()).as("実INSERT時にSpring TXが有効").isTrue();
            return outcomes;
        } finally {
            gate.releaseFirst.countDown();
            for (Future<LoginOutcome> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(COMPLETION_SECONDS, TimeUnit.SECONDS)) {
                    throw new FixtureException("所有workerの回収失敗（REDに数えない）");
                }
            } finally {
                ACTIVE_GATE.compareAndSet(gate, null);
                WORKER.remove();
            }
        }
    }

    /** 返却はservice proxyのcommit完了後。秘密値はhashへ変換し、例外messageをログへ出さない。 */
    private LoginOutcome login(Fixture fixture, int worker) {
        WORKER.set(worker);
        Instant started = Instant.now();
        try {
            Object body = authService.login(new LoginRequest(fixture.email(), PASSWORD, false,
                    "cmp0514-device-" + worker), IP, USER_AGENT).getData();
            if (!(body instanceof LoginResponse response)) {
                throw new FixtureException("ACTIVE/2FAoff fixtureでLoginResponseが返らなかった");
            }
            return new LoginOutcome(true, authTokenService.hashToken(response.getRefreshToken()),
                    started, Instant.now(), "SUCCESS");
        } catch (FixtureException error) {
            throw error;
        } catch (Exception error) {
            SQLException sql = findSqlException(error);
            if (sql == null || sql.getErrorCode() != 1213 || !"40001".equals(sql.getSQLState())) {
                throw new FixtureException("想定deadlock以外のlogin失敗: " + safeFailure(error), error);
            }
            return new LoginOutcome(false, null, started, Instant.now(), safeFailure(error));
        } finally {
            WORKER.remove();
        }
    }

    private void assertSuccessful(List<LoginOutcome> outcomes, long userId) {
        for (int index = 0; index < outcomes.size(); index++) {
            LoginOutcome outcome = outcomes.get(index);
            assertThat(outcome.success()).as("userId=%s worker=%s login+commit: %s",
                    userId, index + 1, outcome.failure()).isTrue();
        }
        if (outcomes.size() == 2) {
            assertThat(outcomes.get(0).tokenHash().equals(outcomes.get(1).tokenHash()))
                    .as("別tokenが発行される").isFalse();
        }
    }

    private void assertCommittedState(Fixture fixture, List<LoginOutcome> outcomes,
                                      int expectedTotal, int expectedActive) throws SQLException {
        try (Connection connection = ownConnection()) {
            assertThat(connection.getAutoCommit()).as("確定状態を読む新規接続").isTrue();
            assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=?", fixture.userId()))
                    .isEqualTo(expectedTotal);
            assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? "
                    + "AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()", fixture.userId()))
                    .as("正本F12.4§5.7のactive上限").isEqualTo(expectedActive);
            for (LoginOutcome outcome : outcomes) {
                assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? "
                        + "AND token_hash=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()",
                        fixture.userId(), outcome.tokenHash())).as("今回発行tokenがcommit後も生存").isEqualTo(1);
            }
            Instant lastLogin = readLastLoginInstant(connection, fixture.userId());
            Instant earliest = outcomes.stream().map(LoginOutcome::started).min(Instant::compareTo).orElseThrow();
            Instant latest = outcomes.stream().map(LoginOutcome::finished).max(Instant::compareTo).orElseThrow();
            assertThat(lastLogin).as("lastLoginAtが過去fixtureから今回の成功login窓へ更新される")
                    .isNotNull().isAfter(fixture.initialLastLogin())
                    .isBetween(earliest.minus(DB_TIMESTAMP_TOLERANCE), latest.plus(DB_TIMESTAMP_TOLERANCE));
        }
    }

    /** DATETIMEのUTC壁時計をCalendar付きJDBCで瞬間へ読み戻し、JVM既定JSTで再解釈しない。 */
    private static Instant readLastLoginInstant(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_login_at FROM users WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new FixtureException("fixture userが実DBに存在しない");
                }
                java.sql.Timestamp value = rows.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                return value == null ? null : value.toInstant();
            }
        }
    }

    private void assertOldestRevoked(Fixture fixture, int expectedRevoked) throws SQLException {
        try (Connection connection = ownConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NOT NULL",
                    fixture.userId())).isEqualTo(expectedRevoked);
            assertThat(count(connection, "SELECT COUNT(*) FROM refresh_tokens WHERE id=? AND revoked_at IS NOT NULL",
                    fixture.existingIds().getFirst())).as("last_used_at最古のfixtureが失効").isEqualTo(1);
        }
    }

    /** P_Sの同一schema/users/PRIMARY/userIdと接続IDを全て束縛する。SQL本文/値は収録しない。 */
    private boolean parentWaitObserved(InsertGate gate, long userId) {
        Long first = gate.connectionIds.get(1);
        Long second = gate.connectionIds.get(2);
        if (first == null || second == null) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                + "AND r.ENGINE=w.ENGINE "
                + "JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND b.ENGINE=w.ENGINE "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID=r.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID=b.THREAD_ID "
                + "WHERE rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=? "
                + "AND r.OBJECT_SCHEMA=DATABASE() AND r.OBJECT_NAME='users' AND r.INDEX_NAME='PRIMARY' "
                + "AND r.LOCK_DATA=? AND r.LOCK_STATUS='WAITING' AND r.LOCK_MODE LIKE 'X%' "
                + "AND b.OBJECT_SCHEMA=DATABASE() AND b.OBJECT_NAME='users' AND b.INDEX_NAME='PRIMARY' "
                + "AND b.LOCK_DATA=? AND b.LOCK_STATUS='GRANTED' AND b.LOCK_MODE LIKE 'X%'";
        try (Connection admin = ownObservationConnection(); PreparedStatement statement = admin.prepareStatement(sql)) {
            statement.setQueryTimeout(OBSERVATION_QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, second);
            statement.setLong(2, first);
            statement.setString(3, Long.toString(userId));
            statement.setString(4, Long.toString(userId));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new FixtureException("P_S wait graphのCOUNTが行を返さなかった");
                }
                return rows.getInt(1) > 0;
            }
        } catch (SQLException error) {
            throw fixtureSql("P_S wait graph取得不能/観測timeout（deadlock REDに数えない）", error);
        }
    }

    private static Connection ownConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection ownObservationConnection() throws SQLException {
        // Awaitilityは実行中のJDBC呼出しを中断しないため、観測専用接続自身のI/Oも有界にする。
        // URLを連結せず、このPropertiesを使うownTCのP_S接続だけへ適用する。
        Properties properties = new Properties();
        properties.setProperty("user", "root");
        properties.setProperty("password", MYSQL.getPassword());
        properties.setProperty("connectTimeout", Integer.toString(OBSERVATION_CONNECT_TIMEOUT_MILLIS));
        properties.setProperty("socketTimeout", Integer.toString(OBSERVATION_SOCKET_TIMEOUT_MILLIS));
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), properties);
    }

    private static int count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new FixtureException("COUNT queryが行を返さなかった");
                }
                return rows.getInt(1);
            }
        }
    }

    private static void awaitLatch(CountDownLatch latch, String reason) {
        try {
            if (!latch.await(COMPLETION_SECONDS, TimeUnit.SECONDS)) {
                throw new FixtureException(reason);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FixtureException(reason, error);
        }
    }

    private static SQLException findSqlException(Throwable error) {
        SQLException first = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (first == null) {
                    first = sql;
                }
                // commit時のbatch例外は実MySQLエラーをnextException側に持つこともある。
                for (SQLException item = sql; item != null; item = item.getNextException()) {
                    if (item.getErrorCode() == 1213 && "40001".equals(item.getSQLState())) {
                        return item;
                    }
                }
            }
        }
        return first;
    }

    private static String safeFailure(Throwable error) {
        SQLException sql = findSqlException(error);
        return error.getClass().getSimpleName() + (sql == null ? "" :
                " SQLState=" + sql.getSQLState() + " errorCode=" + sql.getErrorCode());
    }

    private static FixtureException fixtureSql(String reason, SQLException error) {
        return new FixtureException(reason + " SQLState=" + error.getSQLState() + " errorCode=" + error.getErrorCode());
    }

    /** 一クラス候補内のfixture factory。手書きINSERTやFK無効化を使わない。 */
    private static final class LoginTestFixture {
        static UserEntity user(String email, String hash) {
            return UserEntity.builder().email(email).passwordHash(hash)
                    .lastName("並行").firstName("試験").displayName("CMP0514")
                    .isSearchable(false).locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE).lastLoginAt(LocalDateTime.now().minusDays(1)).build();
        }

        static RefreshTokenEntity token(long userId, String hash, int index) {
            return RefreshTokenEntity.builder().userId(userId).tokenHash(hash).jti(UUID.randomUUID().toString())
                    .rememberMe(false).ipAddress(IP).deviceFingerprint("cmp0514-existing")
                    .lastUsedAt(LocalDateTime.now().minusDays(2).plusMinutes(index))
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
        }
    }

    private record Fixture(long userId, String email, List<Long> existingIds, Instant initialLastLogin) { }

    /** 生token/パスワードを保持しない。失敗理由は例外型とSQLState/errorCodeだけ。 */
    private record LoginOutcome(boolean success, String tokenHash, Instant started, Instant finished, String failure) { }

    /** fixtureの観測失敗を受入assertのREDから分ける。 */
    private static final class FixtureException extends RuntimeException {
        FixtureException(String message) { super(message); }
        FixtureException(String message, Throwable cause) { super(message, cause); }
    }

    /** 各case専用。最初の実INSERT成功だけ保持し、二者のINSERTを無条件には要求しない。 */
    private static final class InsertGate {
        final CountDownLatch firstInserted = new CountDownLatch(1);
        final CountDownLatch secondInserted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final Map<Integer, Long> connectionIds = new ConcurrentHashMap<>();
        final AtomicBoolean firstHeld = new AtomicBoolean();
        final AtomicBoolean firstInsertInTransaction = new AtomicBoolean();
        volatile String observation;

        void inserted(int worker) {
            if (worker == 1 && firstHeld.compareAndSet(false, true)) {
                firstInsertInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                firstInserted.countDown();
                awaitLatch(releaseFirst, "第一INSERT保持の解放timeout");
            } else if (worker == 2) {
                secondInserted.countDown();
            }
        }
    }

    /** 既存RoutingContractITと同じJDBC委譲方式。SQL実行・戻り値・例外・commitを置換しない。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcObservationConfiguration {
        @Bean
        static BeanPostProcessor loginInsertObserver() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof HikariDataSource pool && "dataSource".equals(beanName)
                            ? new ObservedDataSource(pool) : bean;
                }
            };
        }
    }

    /** 実JDBCの成功後だけ観測する、所有Hikari用の薄いwrapper。bind値やSQL本文はログへ出さない。 */
    static final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;

        ObservedDataSource(HikariDataSource pool) { super(pool); this.pool = pool; }

        @Override
        public Connection getConnection() throws SQLException { return observe(pool.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(pool.getConnection(username, password));
        }

        private Connection observe(Connection connection) throws SQLException {
            InsertGate gate = ACTIVE_GATE.get();
            Integer worker = WORKER.get();
            if (gate != null && worker != null) {
                try (var statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT CONNECTION_ID(), @@foreign_key_checks")) {
                    row.next();
                    if (row.getInt(2) != 1) {
                        throw new FixtureException("worker接続のFK検査が無効（REDに数えない）");
                    }
                    // fresh TX retryがpoolの物理接続を再利用しても、最初に重なった2TXの接続IDを保持する。
                    gate.connectionIds.putIfAbsent(worker, row.getLong(1));
                }
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = delegate(connection, method, args);
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement
                                && args != null && args[0] instanceof String sql) {
                            return observeStatement(statement, sql);
                        }
                        return result;
                    });
        }

        private PreparedStatement observeStatement(PreparedStatement statement, String sql) {
            String normalized = sql.replace("`", "").stripLeading().toLowerCase(Locale.ROOT);
            boolean tokenInsert = normalized.startsWith("insert into refresh_tokens");
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        Object result = delegate(statement, method, args);
                        InsertGate gate = ACTIVE_GATE.get();
                        Integer worker = WORKER.get();
                        if (tokenInsert && method.getName().startsWith("execute") && gate != null && worker != null) {
                            gate.inserted(worker);
                        }
                        return result;
                    });
        }

        private static Object delegate(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }

        @Override public void close() { pool.close(); }
    }
}
