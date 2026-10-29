package com.mannschaft.app.errorreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent.Kind;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未実行の M12 結合 AC。実 Spring の公開 M イベントから実 Service / AsyncExecutor /
 * Ingest / DB / AFTER_COMMIT Notify を通り、所有 loopback と自 fixture の bell へ届くことを測る。
 * 共通 MySQL 基底を継承し、追加 Mock / properties / SpringBootTest 設定を持たない。
 *
 * <p>実行前提: test Webhook が空、対象固定コードの既存 report / aggregation が0件、既存 SYSTEM_ADMIN
 * 受信者が0人であること。他者の行・ロールを消して前提を作らず、不成立なら発行前に失敗する。
 * 固定コードの既存集約を変更しないため、本クラスは新しい共通 test context で単独実行する。
 * Webhook 一時変更中に他の JUnit クラスを並走させない。</p>
 *
 * <p>この IT が通っても、M core の commit 後発行、隔離 rollback 時通知0、全未完了を対象にした
 * 30分ちょうど通知0 / 超過通知あり、再試行の抑止は未了。core 接続の別試練が必須。</p>
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("共有 Notifier の空 Webhook を所有 loopback へ一時変更し完全復元するため")
@DisplayName("M12 公開運用警報の実DB・AFTER_COMMIT・loopback 配送")
class ModuleEventOperationalAlertIT extends AbstractMySqlIntegrationTest {
    private static final List<String> CODES = List.of(
            "MODULE_EVENT_QUARANTINED", "MODULE_EVENT_OLDEST_PENDING_30_MIN");
    private static final String REPORT_PREFIX = "java.lang.IllegalStateException: ";
    private final String requestId = UUID.randomUUID().toString();
    private Long ownedUserId;
    private Long ownedRoleId;
    private Long ownedAssignmentId;

    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private ErrorReportNotifier notifier;
    @Autowired private ErrorReportRepository reports;
    @Autowired private ErrorReportAggregator aggregator;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;

    @Test
    void 二種の公開警報が実DBから所有loopbackと自管理者bellへ届く() throws Exception {
        ErrorReportNotifier target = AopTestUtils.getUltimateTargetObject(notifier);
        String originalWebhook = (String) ReflectionTestUtils.getField(target, "slackWebhookUrl");
        // 非空なら秘密をログに出さず、発行・設定変更・fixture 作成より前に止める。
        assertTrue(originalWebhook != null && originalWebhook.isBlank(), "test Webhook が空であることが実行条件");
        Set<Long> beforeReportIds = fixedCodeReportIds();
        assertThat(beforeReportIds).as("既存の固定コード集約は変更しない").isEmpty();
        // Ingest の commit 失敗後は、DB 行なしでも cache だけ残る場合がある。
        assertTrue(aggregationBuffer().asMap().values().stream().noneMatch(value ->
                value instanceof ErrorReportAggregator.AggregatedEntry entry
                        && CODES.stream().anyMatch(code -> (REPORT_PREFIX + code).equals(entry.message()))),
                "対象固定コードの既存 aggregation が0件であることが実行条件");
        assertThat(userRoles.findSystemAdminUserIds()).as("他 fixture へ bell を配送しない").isEmpty();
        awaitAsyncIdle();

        List<String> receivedCodes = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> receiverFailure = new AtomicReference<>();
        HttpServer server = null;
        ExecutorService receiver = null;
        boolean replaced = false;
        try {
            createOwnedAdmin();
            assertThat(userRoles.findSystemAdminUserIds()).containsExactly(ownedUserId);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            receiver = Executors.newSingleThreadExecutor();
            server.setExecutor(receiver);
            String path = "/m12-alert/" + requestId;
            server.createContext(path, exchange -> {
                try {
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    byte[] body = exchange.getRequestBody().readNBytes(65_537);
                    assertThat(body.length).isLessThanOrEqualTo(65_536);
                    String text = objectMapper.readTree(new String(body, StandardCharsets.UTF_8))
                            .path("text").asText();
                    String code = CODES.stream().filter(text::contains).findFirst().orElseThrow();
                    // HTTP 受信スレッドの別接続から見えることが commit 後配送の対照になる。
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_reports "
                            + "WHERE request_id = ? AND error_message = ?", Long.class,
                            requestId, REPORT_PREFIX + code)).isEqualTo(1L);
                    receivedCodes.add(code);
                    exchange.sendResponseHeaders(200, -1);
                } catch (Throwable failed) {
                    receiverFailure.compareAndSet(null, failed);
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            assertTrue(server.getAddress().getAddress().isLoopbackAddress());
            ReflectionTestUtils.setField(target, "slackWebhookUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + path);
            replaced = true;
            publishOwnEvents();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(receiverFailure.get()).isNull();
                assertThat(receivedCodes).containsExactlyInAnyOrderElementsOf(CODES);
                List<ErrorReportEntity> own = ownedReports();
                assertThat(own).hasSize(2);
                assertThat(own).extracting(ErrorReportEntity::getErrorHash).doesNotHaveDuplicates().doesNotContainNull();
                assertThat(own).extracting(ErrorReportEntity::getSeverity).containsOnly(ErrorReportSeverity.HIGH);
                assertThat(own).extracting(ErrorReportEntity::getUserId).containsOnlyNulls();
                assertThat(own).extracting(ErrorReportEntity::getOrganizationId).containsOnlyNulls();
                assertThat(countOwnBell(own)).isEqualTo(2L);
            });
            awaitAsyncIdle();
            Set<Long> created = new HashSet<>(fixedCodeReportIds());
            created.removeAll(beforeReportIds);
            assertThat(created).containsExactlyInAnyOrderElementsOf(ownedReports().stream()
                    .map(ErrorReportEntity::getId).toList());
        } finally {
            // 失敗時もまず空設定へ戻す。非同期完了待ちが失敗しても外部 URL を残さない。
            try {
                if (replaced) {
                    ReflectionTestUtils.setField(target, "slackWebhookUrl", originalWebhook);
                }
                awaitAsyncIdle();
                assertTrue(originalWebhook.equals(ReflectionTestUtils.getField(target, "slackWebhookUrl")),
                        "元の空 Webhook 設定へ完全復元する");
                List<ErrorReportEntity> own = ownedReports();
                int receivedBeforeDisabledCall = receivedCodes.size();
                if (!own.isEmpty()) {
                    notifier.notifySlack(own.getFirst());
                }
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                        assertThat(receivedCodes).hasSize(receivedBeforeDisabledCall));
            } finally {
                try {
                    if (server != null) {
                        server.stop(0);
                    }
                    if (receiver != null) {
                        receiver.shutdownNow();
                        assertTrue(receiver.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
                    }
                } finally {
                    cleanOwnedData(beforeReportIds);
                }
            }
        }
    }

    private void publishOwnEvents() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String previous = MDC.get("requestId");
        MDC.put("requestId", requestId);
        try {
            context.publishEvent(new ModuleEventOperationalAlertEvent(Kind.QUARANTINED));
            context.publishEvent(new ModuleEventOperationalAlertEvent(Kind.OLDEST_PENDING_30_MIN));
        } finally {
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }

    private void createOwnedAdmin() {
        tx.executeWithoutResult(status -> {
            RoleEntity role = roles.findByName("SYSTEM_ADMIN").orElseGet(() -> {
                RoleEntity created = roles.saveAndFlush(RoleEntity.builder().name("SYSTEM_ADMIN")
                        .displayName("システム管理者").priority(1).isSystem(true).build());
                ownedRoleId = created.getId();
                return created;
            });
            UserEntity user = users.saveAndFlush(UserEntity.builder().email("m12-" + requestId + "@example.invalid")
                    .lastName("試練").firstName("管理者").displayName("M12試練管理者")
                    .isSearchable(false).locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE).build());
            ownedUserId = user.getId();
            ownedAssignmentId = userRoles.saveAndFlush(UserRoleEntity.builder()
                    .userId(ownedUserId).roleId(role.getId()).build()).getId();
        });
    }

    private Set<Long> fixedCodeReportIds() {
        return Set.copyOf(jdbc.queryForList("SELECT id FROM error_reports WHERE error_message IN (?, ?)",
                Long.class, REPORT_PREFIX + CODES.get(0), REPORT_PREFIX + CODES.get(1)));
    }

    private List<ErrorReportEntity> ownedReports() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM error_reports WHERE request_id = ? "
                        + "AND error_message IN (?, ?)", Long.class,
                requestId, REPORT_PREFIX + CODES.get(0), REPORT_PREFIX + CODES.get(1));
        return reports.findAllById(ids);
    }

    private long countOwnBell(List<ErrorReportEntity> own) {
        long count = 0;
        for (ErrorReportEntity report : own) {
            count += jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                    + "AND notification_type = 'ERROR_REPORT_CRITICAL' AND source_type = 'ERROR_REPORT' "
                    + "AND source_id = ?", Long.class, ownedUserId, report.getId());
        }
        return count;
    }

    private void awaitAsyncIdle() {
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(30)).until(() ->
                List.of("event-pool", "notification-dispatch-pool").stream().allMatch(name -> {
                    ThreadPoolTaskExecutor pool = context.getBean(name, ThreadPoolTaskExecutor.class);
                    return pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty();
                }));
    }

    private Cache<?, ?> aggregationBuffer() {
        Object target = AopTestUtils.getUltimateTargetObject(aggregator);
        Cache<?, ?> buffer = (Cache<?, ?>) ReflectionTestUtils.getField(target, "aggregationBuffer");
        assertThat(buffer).isNotNull();
        return buffer;
    }

    private void cleanOwnedData(Set<Long> beforeReportIds) {
        List<ErrorReportEntity> own = ownedReports();
        Cache<?, ?> buffer = aggregationBuffer();
        for (ErrorReportEntity report : own) {
            assertThat(beforeReportIds).doesNotContain(report.getId());
            buffer.asMap().remove(report.getErrorHash()); // 自分が作った2 hash だけ。global drain はしない。
            jdbc.update("DELETE FROM notifications WHERE user_id = ? AND source_type = 'ERROR_REPORT' "
                    + "AND source_id = ? AND notification_type = 'ERROR_REPORT_CRITICAL'", ownedUserId, report.getId());
            jdbc.update("DELETE FROM error_report_activities WHERE error_report_id = ?", report.getId());
            jdbc.update("DELETE FROM error_report_occurrences WHERE error_report_id = ?", report.getId());
            jdbc.update("DELETE FROM error_report_ai_analyses WHERE error_report_id = ?", report.getId());
            jdbc.update("DELETE FROM error_reports WHERE id = ? AND request_id = ?", report.getId(), requestId);
        }
        tx.executeWithoutResult(status -> {
            if (ownedAssignmentId != null) userRoles.deleteById(ownedAssignmentId);
            if (ownedUserId != null) {
                jdbc.update("DELETE FROM notification_type_preferences WHERE user_id = ?", ownedUserId);
                jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", ownedUserId);
                jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", ownedUserId);
                users.deleteById(ownedUserId);
            }
            if (ownedRoleId != null) roles.deleteById(ownedRoleId);
        });
    }
}
