package com.mannschaft.app.moduleevent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.ModuleEventAdminApi;
import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理2EPの先行試練。実Securityフィルタ・実Bean・実MySQLを通し、実commit後を観測する。
 * テスト全体のrollback TXや追加MockitoBeanは使わない。
 * publicationは公式SPI、補助行はEntityManager経由で作成し、手書きINSERTを使わない。
 * 未来のpublicationDateとleaseUntilにより、後続のretry schedulerが検体をclaimしない。
 * 再開監査はmoduleevent専用履歴の実行者・trim済理由・DB格納UTC時刻と原子性を検査する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventAdminApiIT extends AbstractMySqlIntegrationTest {
    private static final String ROOT = "/api/v1/system-admin/module-events";
    private static final String ACTOR_ID = "919201";
    private static final String OTHER_ACTOR_ID = "919202";
    private static final String FAILURE_CODE = "LISTENER_FAILED";
    private static final String SYNTHETIC_SECRET = "module-admin-it-private-payload";
    private static final int RETRY_LIMIT = 10;
    private static final int MYSQL_DATA_TOO_LONG = 1406;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ModuleEventAdminApi adminApi;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;
    private final List<UUID> ownedPublications = new ArrayList<>();

    @AfterEach
    void 自分の検体だけを掃除する() {
        for (UUID publicationId : ownedPublications) {
            byte[] id = bytes(publicationId);
            jdbc.update("DELETE FROM module_event_resume_audits WHERE publication_id = ?", id);
            jdbc.update("DELETE FROM event_publication_attempts WHERE publication_id = ?", id);
            jdbc.update("DELETE FROM event_publication WHERE id = ?", id);
        }
    }

    @Test
    void 匿名は両EPとも401になる() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/{id}/resume", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再開\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "ADMIN", "DEPUTY_ADMIN", "ORGANIZATION_ADMIN"})
    void 他テナントを含む一般と組織チーム管理者は両EPとも403になる(String role) throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        RequestPostProcessor otherActor = user(OTHER_ACTOR_ID).roles(role);

        mvc.perform(get(ROOT).with(otherActor)).andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/{id}/resume", publicationId).with(otherActor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再開\"}"))
                .andExpect(status().isForbidden());
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
    }

    @Test
    void SYSTEM_ADMINは他テナントの隔離を含む一覧を取得できる() throws Exception {
        UUID own = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        UUID otherTenant = fixture(ModuleEventAttemptStatus.QUARANTINED, 8L);
        UUID pending = fixture(ModuleEventAttemptStatus.PENDING, 7L);

        MvcResult result = mvc.perform(get(ROOT).with(systemAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20)).andReturn();
        JsonNode root = mapper.readTree(result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        root.path("data").forEach(row -> ids.add(row.path("publicationId").asText()));
        assertThat(ids).contains(own.toString(), otherTenant.toString()).doesNotContain(pending.toString());
        JsonNode row = root.path("data").get(0);
        Set<String> fields = new HashSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("publicationId", "eventType", "listenerId",
                "publicationDate", "lastAttemptAt", "quarantinedAt", "retryCount", "failureCode");
        assertThat(row.path("retryCount").asInt()).isEqualTo(RETRY_LIMIT);
        assertThat(row.path("failureCode").asText()).isEqualTo(FAILURE_CODE);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(SYNTHETIC_SECRET, "serialized_event", "serializedEvent", "stackTrace", "exception");
    }

    @Test
    void 隔離0件は空のdataとページメタを返す() throws Exception {
        mvc.perform(get(ROOT).with(systemAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));
    }

    @Test
    void ページングは重複せず範囲外ページは空になる() throws Exception {
        fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        JsonNode first = listPage(0, 1);
        JsonNode second = listPage(1, 1);
        JsonNode beyond = listPage(2, 1);

        assertThat(first.path("meta").path("total").asLong()).isEqualTo(2);
        assertThat(first.path("meta").path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("data").size()).isEqualTo(1);
        assertThat(second.path("data").size()).isEqualTo(1);
        assertThat(first.path("data").get(0).path("publicationId").asText())
                .isNotEqualTo(second.path("data").get(0).path("publicationId").asText());
        assertThat(beyond.path("data").size()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101"})
    void ページの境界外入力は400になる(int page, int size) throws Exception {
        mvc.perform(get(ROOT).with(systemAdmin()).param("page", Integer.toString(page))
                        .param("size", Integer.toString(size))).andExpect(status().isBadRequest());
    }

    @Test
    void 最大ページサイズ100を受け付ける() throws Exception {
        mvc.perform(get(ROOT).with(systemAdmin()).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "IN_PROGRESS", "UNKNOWN", ""})
    void 隔離以外のstatus指定は400になる(String requestedStatus) throws Exception {
        mvc.perform(get(ROOT).with(systemAdmin()).param("status", requestedStatus))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void 理由はtrim後の最小最大長で再開でき試行回数をリセットする(int length) throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        UUID oldToken = claimToken(publicationId);
        String before = jdbc.queryForObject("SELECT CAST(UTC_TIMESTAMP(6) AS CHAR)", String.class);
        String body = mapper.writeValueAsString(Map.of("reason", "  " + "再".repeat(length) + "  "));

        mvc.perform(post(ROOT + "/{id}/resume", publicationId).with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent()).andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).isEmpty());
        assertState(publicationId, "PENDING", 0);
        assertThat(auditCount(publicationId)).isEqualTo(1L);
        assertThat(claimToken(publicationId)).isNotEqualTo(oldToken);
        assertThat(jdbc.queryForObject("SELECT quarantined_at FROM event_publication_attempts "
                + "WHERE publication_id = ?", java.sql.Timestamp.class, bytes(publicationId))).isNull();
        Map<String, Object> audit = jdbc.queryForMap("SELECT id, actor_user_id, reason, created_at "
                + "FROM module_event_resume_audits WHERE publication_id = ?", bytes(publicationId));
        assertThat(((Number) audit.get("actor_user_id")).longValue()).isEqualTo(Long.parseLong(ACTOR_ID));
        assertThat(audit.get("reason")).isEqualTo("再".repeat(length));
        assertThat(audit.get("created_at")).isNotNull();
        ByteBuffer auditId = ByteBuffer.wrap((byte[]) audit.get("id"));
        assertThat(new UUID(auditId.getLong(), auditId.getLong()).version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_audits "
                + "WHERE publication_id = ? AND created_at >= CAST(? AS DATETIME(6)) "
                + "AND created_at <= UTC_TIMESTAMP(6)", Long.class, bytes(publicationId), before)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"})
    void 理由の未指定とnull空文字空白は400で状態を変えない(String body) throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        mvc.perform(post(ROOT + "/{id}/resume", publicationId).with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
    }

    @Test
    void trim後501文字の理由は400で状態を変えない() throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        resume(publicationId, "  " + "再".repeat(501) + "  ", 400);
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
    }

    @Test
    void 不在publicationは404になる() throws Exception {
        resume(UUID.randomUUID(), "運用確認済み", 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "IN_PROGRESS"})
    void 隔離以外の状態は409で状態を変えない(String initialStatus) throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.valueOf(initialStatus), 7L);
        resume(publicationId, "運用確認済み", 409);
        assertState(publicationId, initialStatus, 0);
        assertThat(auditCount(publicationId)).isZero();
    }

    @Test
    void 同じ隔離への同時再開は一方だけ204で他方は409になる() throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("同時再開の開始待機が時間切れ");
                }
                return mvc.perform(post(ROOT + "/{id}/resume", publicationId).with(systemAdmin())
                                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"運用確認済み\"}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(requests.get(0).get(20, TimeUnit.SECONDS), requests.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 409);
        }
        assertState(publicationId, "PENDING", 0);
        assertThat(auditCount(publicationId)).isEqualTo(1L);
    }

    @Test
    void 明示rollbackで再開状態と監査履歴が両方戻る() {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        UUID oldToken = claimToken(publicationId);
        var previousContext = SecurityContextHolder.getContext();
        var actorContext = SecurityContextHolder.createEmptyContext();
        actorContext.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        SecurityContextHolder.setContext(actorContext);
        try {
            assertThatCode(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                adminApi.resume(publicationId, "運用確認済み");
                entityManager.flush();
                assertState(publicationId, "PENDING", 0);
                assertThat(auditCount(publicationId)).isEqualTo(1L);
                status.setRollbackOnly();
            })).doesNotThrowAnyException();
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
        assertThat(claimToken(publicationId)).isEqualTo(oldToken);
        assertThat(auditCount(publicationId)).isZero();
    }

    /**
     * productionが生成する正常監査1件をflushした後、同じM TXの追加監査insertをDB制約で失敗させる。
     * production自身の監査insertだけを狙うfault injectionとは区別する。
     * 非strict SQLで501文字が切り捨てられても、例外不在をassertion failureとして検出する。
     */
    @Test
    void 同じMトランザクションの監査insert失敗は再開と履歴を原子的に戻す() {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        UUID oldToken = claimToken(publicationId);
        AtomicBoolean invalidAuditAttempted = new AtomicBoolean();
        var previousContext = SecurityContextHolder.getContext();
        var actorContext = SecurityContextHolder.createEmptyContext();
        actorContext.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        SecurityContextHolder.setContext(actorContext);
        Throwable failure;
        try {
            failure = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                adminApi.resume(publicationId, "運用確認済み");
                entityManager.flush();
                assertState(publicationId, "PENDING", 0);
                assertThat(auditCount(publicationId)).isEqualTo(1L);
                invalidAuditAttempted.set(true);
                entityManager.persist(ModuleEventResumeAuditEntity.builder()
                        .publicationId(publicationId).actorUserId(Long.parseLong(ACTOR_ID))
                        .reason("再".repeat(501)).createdAt(LocalDateTime.now()).build());
                entityManager.flush();
            }));
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        assertThat(invalidAuditAttempted).isTrue();
        assertThat(failure).isNotNull().hasRootCauseInstanceOf(SQLException.class);
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("22001");
        assertThat(((SQLException) rootCause).getErrorCode()).isEqualTo(MYSQL_DATA_TOO_LONG);
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
        assertThat(claimToken(publicationId)).isEqualTo(oldToken);
        assertThat(auditCount(publicationId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON", "QUERY"})
    void actor偽装入力があっても監査には現在の認証主体を記録する(String input) throws Exception {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        var request = post(ROOT + "/{id}/resume", publicationId).with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON);
        if ("JSON".equals(input)) {
            request.content(mapper.writeValueAsString(Map.of(
                    "reason", "運用確認済み", "actorUserId", Long.parseLong(OTHER_ACTOR_ID))));
        } else {
            request.param("actorUserId", OTHER_ACTOR_ID)
                    .content(mapper.writeValueAsString(Map.of("reason", "運用確認済み")));
        }

        mvc.perform(request).andExpect(status().isNoContent());
        assertThat(auditCount(publicationId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM module_event_resume_audits "
                + "WHERE publication_id = ?", Long.class, bytes(publicationId)))
                .isEqualTo(Long.parseLong(ACTOR_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "ADMIN", "DEPUTY_ADMIN", "ORGANIZATION_ADMIN"})
    void 直接APIも一般と組織チーム管理者による再開を拒否する(String role) {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        var previousContext = SecurityContextHolder.getContext();
        var actorContext = SecurityContextHolder.createEmptyContext();
        actorContext.setAuthentication(new UsernamePasswordAuthenticationToken(OTHER_ACTOR_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(actorContext);
        try {
            Throwable failure = catchThrowable(() -> adminApi.resume(
                    publicationId, "運用確認済み"));
            assertThat(failure).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
        assertThat(auditCount(publicationId)).isZero();
    }

    @Test
    void 直接APIも認証主体がない再開を拒否する() {
        UUID publicationId = fixture(ModuleEventAttemptStatus.QUARANTINED, 7L);
        var previousContext = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        try {
            Throwable failure = catchThrowable(() -> adminApi.resume(
                    publicationId, "運用確認済み"));
            assertThat(failure).isInstanceOf(
                    org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        assertState(publicationId, "QUARANTINED", RETRY_LIMIT);
        assertThat(auditCount(publicationId)).isZero();
    }

    private long auditCount(UUID publicationId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_audits "
                + "WHERE publication_id = ?", Long.class, bytes(publicationId));
        assertThat(count).isNotNull();
        return count;
    }

    private JsonNode listPage(int page, int size) throws Exception {
        MvcResult result = mvc.perform(get(ROOT).with(systemAdmin()).param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void resume(UUID id, String reason, int expectedStatus) throws Exception {
        mvc.perform(post(ROOT + "/{id}/resume", id).with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().is(expectedStatus));
    }

    private RequestPostProcessor systemAdmin() {
        return user(ACTOR_ID).roles("SYSTEM_ADMIN");
    }

    /**
     * 公式SPIの本表とアプリ所有補助Entityを同じTXでcommitする検体factory。
     * 未来publicationはまだ配送時期に達していないため、後続schedulerと奪い合わない。
     */
    private UUID fixture(ModuleEventAttemptStatus attemptStatus, Long organizationId) {
        UUID id = new TransactionTemplate(transactions).execute(status -> {
            Instant publicationDate = Instant.now().plus(30, ChronoUnit.DAYS);
            LocalDateTime future = LocalDateTime.ofInstant(publicationDate, ZoneOffset.UTC);
            AdminFixtureEvent event = new AdminFixtureEvent(UUID.randomUUID(), organizationId,
                    future, SYNTHETIC_SECRET);
            TargetEventPublication publication = publications.create(TargetEventPublication.of(event,
                    PublicationTargetIdentifier.of("module-admin-it-" + UUID.randomUUID()), publicationDate));
            entityManager.persist(EventPublicationAttemptEntity.builder()
                    .publicationId(publication.getIdentifier())
                    .status(attemptStatus).retryCount(attemptStatus == ModuleEventAttemptStatus.QUARANTINED ? RETRY_LIMIT : 0)
                    .claimToken(UUID.randomUUID()).lastAttemptAt(future).leaseUntil(future)
                    .quarantinedAt(attemptStatus == ModuleEventAttemptStatus.QUARANTINED ? future : null)
                    .failureCode(attemptStatus == ModuleEventAttemptStatus.QUARANTINED ? FAILURE_CODE : null)
                    .createdAt(future).updatedAt(future).version(0).build());
            entityManager.flush();
            return publication.getIdentifier();
        });
        ownedPublications.add(id);
        return id;
    }

    private void assertState(UUID publicationId, String expectedStatus, int expectedRetryCount) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status, retry_count FROM event_publication_attempts "
                + "WHERE publication_id = ?", bytes(publicationId));
        assertThat(row.get("status")).isEqualTo(expectedStatus);
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(expectedRetryCount);
    }

    private UUID claimToken(UUID publicationId) {
        byte[] value = jdbc.queryForObject("SELECT claim_token FROM event_publication_attempts "
                + "WHERE publication_id = ?", byte[].class, bytes(publicationId));
        assertThat(value).hasSize(16);
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** APIに絶対に返してはいけない合成payloadを持つ、本表の直列化用検体。 */
    record AdminFixtureEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt,
            String syntheticSecret) implements ReliableEvent {
    }
}
