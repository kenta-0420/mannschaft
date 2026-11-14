package com.mannschaft.app.shiftbudget;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRetryExecutor;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CMP0303: 初回配送と累積再送の受信者進捗を、実Flyway/MySQLと実サービスproxyで検証する試練。
 *
 * <p>初回失敗の記録はretry_count=0であり、executeの試行回数とは分けて観測する。
 * テスト全体を@Transactionalで包まず、各試行の完了後に別TXで同じfailed_eventを読み直す。
 * 外部dispatchとValkey境界だけを差し替え、通知INSERT・失敗CHECK・保存・commitを実物で通す。
 * 正常にcommitする逐次再送の契約であり、crash後や並行manualのexactly-onceを証明しない。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.docker.compose.enabled=false",
        "mannschaft.websocket.relay.enabled=false",
        "mannschaft.email.simulate=true",
        "STRIPE_BILLING_PORTAL_CONFIGURATION_ID="
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("CMP0303 累積再送の受信者進捗（実Flyway/MySQL）")
class ShiftBudgetNotificationRetryProgressIT {

    private static final String TYPE = "SHIFT_BUDGET_THRESHOLD_ALERT";
    private static final String CONSTRAINT = "chk_cmp0303_notification_failure";
    private static final long ORGANIZATION_ID = 830300L;
    private static final AtomicLong IDS = new AtomicLong(830300L);

    /** 接続先は専用TCに固定する。Docker不在で本番・共有DBへfallbackしない。 */
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cmp0303_retry_contract")
            .withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw,size=512m"))
            .withCommand("--log-bin-trust-function-creators=1")
            .withReuse(false);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void configureOwnedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @MockitoBean private NotificationDispatchService externalDispatch;
    /** 共通MySQL基底と同じ外部Valkey境界。実LocaleCacheはDBとインメモリcacheを使う。 */
    @MockitoBean private StringRedisTemplate redisTemplate;
    @Autowired private ShiftBudgetRetryExecutor executor;
    @Autowired private ShiftBudgetFailedEventService failedEvents;
    @Autowired private ShiftBudgetFailedEventRepository repository;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager em;

    private Fixture fixture;
    private boolean constraintApplied;

    private record Fixture(long sourceId, List<Long> users) { }

    @BeforeEach
    void prepareOwnedFixture() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(AopUtils.isAopProxy(executor)).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("cmp0303_retry_contract");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Long.class)).isPositive();
        fixture = new Fixture(IDS.incrementAndGet(),
                List.of(IDS.incrementAndGet(), IDS.incrementAndGet(), IDS.incrementAndGet()));
        transactions.executeWithoutResult(tx -> fixture.users().forEach(userId ->
                MembershipTestHelper.insertActiveUser(em, userId)));
    }

    @AfterEach
    void removeOnlyOwnedFixture() {
        unblock();
        if (fixture != null) {
            jdbc.update("DELETE FROM notifications WHERE notification_type = ? AND source_id = ?",
                    TYPE, fixture.sourceId());
            jdbc.update("DELETE FROM shift_budget_failed_events WHERE source_id = ? AND organization_id = ?",
                    fixture.sourceId(), ORGANIZATION_ID);
            fixture.users().forEach(userId -> jdbc.update("DELETE FROM users WHERE id = ?", userId));
        }
    }

    @Test
    @DisplayName("初回: Aだけ成功なら失敗行はBC・count0を保持する（既存契約の対照）")
    void 初回通知の成功者を失敗行へ混ぜない() throws Exception {
        long id = publishInitialPartialFailure();

        assertProgress(id, 0, ShiftBudgetFailedEventStatus.PENDING, fixture.users().subList(1, 3));
        assertNotificationCounts(1, 0, 0);
    }

    @Test
    @DisplayName("初回A成功→再送B成功C失敗→C成功でも同じ失敗行で各人1件だけ残る")
    void 初回と再送の部分成功を跨いでも成功者へ再送しない() throws Exception {
        long id = publishInitialPartialFailure();
        blockOnly(fixture.users().subList(2, 3));

        assertThat(executor.execute(reload(id))).isFalse();
        assertProgress(id, 1, ShiftBudgetFailedEventStatus.PENDING, fixture.users().subList(2, 3));
        assertNotificationCounts(1, 1, 0);

        unblock();
        assertThat(executor.execute(reload(id))).isTrue();
        assertTerminal(id, 2);
        assertNotificationCounts(1, 1, 1);
    }

    @Test
    @DisplayName("旧JSONのABC→BC→C→成功を独立TXで再読しても各人の通知は1件だけ")
    void 累積再送の未完了集合と同じ行の試行回数を保持する() throws Exception {
        long id = failedEvents.recordFailure(ORGANIZATION_ID, ShiftBudgetFailedEventType.NOTIFICATION_SEND,
                fixture.sourceId(), Map.of(
                        "user_ids", fixture.users(), "type", TYPE,
                        "source_type", "SHIFT_BUDGET_ALLOCATION", "source_id", fixture.sourceId(),
                        "scope_id", ORGANIZATION_ID, "threshold_percent", 120,
                        "title", "旧件名", "body", "旧本文", "unknown_field", "preserved"), "initial").getId();
        // キー無しの旧payloadを実MySQL JSONで往復させ、Integer/Longの差に依存しない。
        assertProgress(id, 0, ShiftBudgetFailedEventStatus.PENDING, fixture.users());
        blockOnly(fixture.users().subList(1, 3));

        assertThat(executor.execute(reload(id))).isFalse();
        assertProgress(id, 1, ShiftBudgetFailedEventStatus.PENDING, fixture.users().subList(1, 3));
        assertNotificationCounts(1, 0, 0);

        blockOnly(fixture.users().subList(2, 3));
        assertThat(executor.execute(reload(id))).isFalse();
        assertProgress(id, 2, ShiftBudgetFailedEventStatus.PENDING, fixture.users().subList(2, 3));
        assertNotificationCounts(1, 1, 0);

        unblock();
        assertThat(executor.execute(reload(id))).isTrue();
        assertTerminal(id, 3);
        assertThat(objectMapper.readTree(reload(id).getPayload()).path("unknown_field").asText())
                .isEqualTo("preserved");
        assertNotificationCounts(1, 1, 1);
    }

    private long publishInitialPartialFailure() {
        blockOnly(fixture.users().subList(1, 3));
        transactions.executeWithoutResult(tx -> publisher.publishEvent(new BudgetThresholdAlertTriggeredEvent(
                fixture.sourceId(), fixture.sourceId(), ORGANIZATION_ID, 120, fixture.users())));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(countFailedEvents()).isEqualTo(1));
        return jdbc.queryForObject("SELECT id FROM shift_budget_failed_events WHERE source_id = ? "
                + "AND organization_id = ?", Long.class, fixture.sourceId(), ORGANIZATION_ID);
    }

    /** 既存BoundaryITと同じ実DB CHECK故障。fixtureの未成功者だけを拒否する。 */
    private void blockOnly(List<Long> userIds) {
        unblock();
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT " + CONSTRAINT
                + " CHECK (notification_type <> '" + TYPE + "' OR source_id <> " + fixture.sourceId()
                + " OR user_id NOT IN (" + ids + "))");
        constraintApplied = true;
    }

    private void unblock() {
        if (constraintApplied) {
            jdbc.execute("ALTER TABLE notifications DROP CHECK " + CONSTRAINT);
            constraintApplied = false;
        }
    }

    private ShiftBudgetFailedEventEntity reload(long id) {
        return transactions.execute(tx -> {
            em.clear();
            return repository.findById(id).orElseThrow();
        });
    }

    private void assertProgress(long id, int attempts, ShiftBudgetFailedEventStatus status,
                                List<Long> pending) throws Exception {
        ShiftBudgetFailedEventEntity actual = reload(id);
        assertThat(actual.getId()).isEqualTo(id);
        assertThat(actual.getRetryCount()).isEqualTo(attempts);
        assertThat(actual.getStatus()).isEqualTo(status);
        JsonNode payload = objectMapper.readTree(actual.getPayload());
        List<Long> ids = objectMapper.convertValue(payload.path("user_ids"), new TypeReference<>() { });
        assertThat(ids).containsExactlyElementsOf(pending);
        assertThat(countFailedEvents()).isEqualTo(1);
    }

    private void assertTerminal(long id, int attempts) {
        ShiftBudgetFailedEventEntity actual = reload(id);
        assertThat(actual.getId()).isEqualTo(id);
        assertThat(actual.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.SUCCEEDED);
        assertThat(actual.getRetryCount()).isEqualTo(attempts);
        assertThat(countFailedEvents()).isEqualTo(1);
    }

    private int countFailedEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events "
                + "WHERE source_id = ? AND organization_id = ?", Integer.class,
                fixture.sourceId(), ORGANIZATION_ID);
    }

    private void assertNotificationCounts(long a, long b, long c) {
        List<Long> expected = List.of(a, b, c);
        for (int i = 0; i < fixture.users().size(); i++) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications "
                            + "WHERE notification_type = ? AND source_id = ? AND user_id = ?",
                    Long.class, TYPE, fixture.sourceId(), fixture.users().get(i)))
                    .as("受信者%sのcommit済み通知件数", fixture.users().get(i))
                    .isEqualTo(expected.get(i));
        }
    }
}
