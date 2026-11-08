package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.service.AuditLogQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 実 MySQL の同一監査行を JPA と JDBC の両経路で読み、日時の瞬間・日付検索・本人範囲を照合する。
 * 試験行は Repository から作り、固定日時用の JPQL 更新は rollback される Testcontainers 内に限定する。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AuditLogTimezoneContractIT extends AbstractMySqlIntegrationTest {

    private static final long OWNER = 930081L;
    private static final long OTHER = 930082L;
    private static final long TEAM = 930091L;
    private static final long ORGANIZATION = 930092L;
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 19, 0, 0);
    private static final LocalDateTime DAY_END = DAY_START.plusDays(1).minusSeconds(1);

    @Autowired private AuditLogRepository repository;
    @Autowired private AuditLogQueryService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccessControlService accessControlService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    @AfterEach
    void clearTimezone() {
        TimezoneContextHolder.clear();
    }

    @Test
    void 同一MySQL行をJPAとJDBCで読んだ日時が同一瞬間かつJST表示になる() throws Exception {
        AuditLogEntity saved = repository.saveAndFlush(log(OWNER));
        long id = saved.getId();
        LocalDateTime createdAt = saved.getCreatedAt().truncatedTo(ChronoUnit.SECONDS);
        em.clear();
        LocalDateTime jpaValue = repository.findById(id).orElseThrow().getCreatedAt();
        Object raw = jdbc.queryForObject("SELECT created_at FROM audit_logs WHERE id = ?",
                (rs, rowNum) -> rs.getObject("created_at"), id);
        assertThat(raw).isNotNull();
        System.out.printf("[audit-timezone] shared JDBC type=%s JPA instant=%s%n",
                raw.getClass().getName(), jpaValue.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
        assertThat(jpaValue.truncatedTo(ChronoUnit.SECONDS)).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class))
                .as("専用 MySQL の接続セッションは製品と同じ UTC").isZero();

        var result = service.getMyLogs(OWNER, null, null, null, null, null, 5);
        var response = result.getData().stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
        assertThat(response.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                .as("同じ行の JPA/JDBC 復元値。実 Connector/J 戻り型=%s", raw.getClass().getName())
                .isEqualTo(jpaValue.truncatedTo(ChronoUnit.SECONDS));
        assertThat(service.findBySourceAndSourceId("timezone-contract", 930001L, 5).getFirst().getCreatedAt()
                .truncatedTo(ChronoUnit.SECONDS)).isEqualTo(jpaValue.truncatedTo(ChronoUnit.SECONDS));
        TimezoneContextHolder.set(UserZoneLocalDateTimeParser.SERVER_ZONE);
        String serialized = objectMapper.valueToTree(response).get("createdAt").asText();
        assertThat(OffsetDateTime.parse(serialized).toInstant())
                .isEqualTo(jpaValue.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "team", "organization"})
    void 管理者とチーム組織の日時検索もJST日付境界の両端を含む(String scope) {
        fixture(OWNER, DAY_START.minusSeconds(1));
        long first = fixture(OWNER, DAY_START);
        long second = fixture(OWNER, DAY_START.plusSeconds(1));
        long third = fixture(OWNER, DAY_END);
        fixture(OWNER, DAY_END.plusSeconds(1));
        fixture(OTHER, DAY_START);
        // 認可自体は既存スコープ契約に委ね、ここでは実 JDBC の日時条件に検証を限定する。
        AuditLogQueryService query = new AuditLogQueryService(mock(AccessControlService.class), jdbc);
        var rows = switch (scope) {
            case "admin" -> query.getAdminLogs(OWNER, OWNER, null, null, null,
                    null, null, null, DAY_START, DAY_END, 0, 20).getData();
            case "team" -> query.getTeamAuditLogs(OWNER, TEAM, OWNER,
                    null, null, DAY_START, DAY_END, null, 20).getData();
            case "organization" -> query.getOrganizationAuditLogs(OWNER, ORGANIZATION, OWNER,
                    null, null, DAY_START, DAY_END, null, 20).getData();
            default -> throw new IllegalArgumentException(scope);
        };

        assertThat(rows).extracting(row -> row.getId()).containsExactly(third, second, first);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getUserId()).isEqualTo(OWNER);
            assertThat(row.getCreatedAt()).isBetween(DAY_START, DAY_END);
        });
    }

    @Test
    void 本番と同じserverTimezoneUTC接続でもJPAと監査参照が同一瞬間になる() {
        // 専用 Testcontainers だけに接続する。別接続で見えるよう、この1行だけ独立TXでcommitする。
        long owner = 930083L;
        TransactionTemplate fixtureTx = new TransactionTemplate(transactionManager);
        fixtureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AuditLogEntity saved = fixtureTx.execute(status -> repository.saveAndFlush(log(owner)));
        assertThat(saved).isNotNull();
        String url = MYSQL.getJdbcUrl();
        DriverManagerDataSource utcDataSource = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "serverTimezone=UTC",
                MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate utcJdbc = new JdbcTemplate(utcDataSource);
        try {
            Object raw = utcJdbc.queryForObject("SELECT created_at FROM audit_logs WHERE id = ?",
                    (rs, rowNum) -> rs.getObject("created_at"), saved.getId());
            assertThat(raw).isNotNull();
            LocalDateTime jpaValue = repository.findById(saved.getId()).orElseThrow().getCreatedAt();
            System.out.printf("[audit-timezone] serverTimezone=UTC JDBC type=%s JPA instant=%s%n",
                    raw.getClass().getName(), jpaValue.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
            AuditLogQueryService utcService = new AuditLogQueryService(accessControlService, utcJdbc);

            var result = utcService.getMyLogs(owner, null, null, null, null, null, 5);

            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().getFirst().getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
                    .as("serverTimezone=UTC の実 Connector/J 戻り型=%s", raw.getClass().getName())
                    .isEqualTo(jpaValue.truncatedTo(ChronoUnit.SECONDS));
        } finally {
            // commitした試験行だけを確実に削除し、共有runtimeや他テスト行には触れない。
            utcJdbc.update("DELETE FROM audit_logs WHERE id = ? AND user_id = ?", saved.getId(), owner);
        }
    }

    @Test
    void JST日付境界の両端を含めて本人行だけをカーソルで重複欠落なく取得する() {
        fixture(OWNER, DAY_START.minusSeconds(1));
        long first = fixture(OWNER, DAY_START);
        long second = fixture(OWNER, DAY_START.plusSeconds(1));
        long third = fixture(OWNER, DAY_END);
        fixture(OWNER, DAY_END.plusSeconds(1));
        fixture(OTHER, DAY_START);
        List<Long> expected = List.of(first, second, third).stream().sorted(Comparator.reverseOrder()).toList();
        List<Long> actual = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page <= expected.size(); page++) {
            var result = service.getMyLogs(OWNER, null, null, DAY_START, DAY_END, cursor, 1);
            for (var row : result.getData()) {
                assertThat(row.getUserId()).isEqualTo(OWNER);
                assertThat(row.getCreatedAt()).isBetween(DAY_START, DAY_END);
                actual.add(row.getId());
            }
            if (!result.getMeta().isHasNext()) {
                assertThat(result.getMeta().getNextCursor()).isNull();
                break;
            }
            assertThat(result.getMeta().getNextCursor()).isNotEqualTo(cursor);
            cursor = result.getMeta().getNextCursor();
        }

        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void 本人の行が0件なら他人の行や日時条件なしでも返さない() {
        fixture(OTHER, DAY_START);

        var result = service.getMyLogs(OWNER, null, null, null, null, null, 5);

        assertThat(result.getData()).isEmpty();
        assertThat(result.getMeta().isHasNext()).isFalse();
        assertThat(result.getMeta().getNextCursor()).isNull();
    }

    private long fixture(long userId, LocalDateTime createdAt) {
        long id = repository.saveAndFlush(log(userId)).getId();
        assertThat(em.createQuery("UPDATE AuditLogEntity a SET a.createdAt = :createdAt WHERE a.id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", id).executeUpdate()).isEqualTo(1);
        em.clear();
        return id;
    }

    private AuditLogEntity log(long userId) {
        return AuditLogEntity.builder().userId(userId).teamId(TEAM).organizationId(ORGANIZATION)
                .eventType("LOGIN_SUCCESS")
                .metadata("{\"source\":\"timezone-contract\",\"source_id\":930001}").build();
    }
}
