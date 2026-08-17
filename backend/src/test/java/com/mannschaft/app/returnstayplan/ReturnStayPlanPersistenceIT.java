package com.mannschaft.app.returnstayplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** MySQL authorization, persistence, race, paging and query-count contracts. */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(ReturnStayPlanPersistenceIT.FixedClockConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ReturnStayPlanPersistenceIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);
    private static final long OWNER_ID = 923001L;
    private static final long VIEWER_ID = 923002L;
    private static final long SYSTEM_ADMIN_ID = 923003L;
    private static final long TEAM_ID = 923010L;
    private static final String TEAM_SLUG = "f0211-persistence-team";

    @Autowired
    private ReturnStayPlanService service;

    @Autowired
    private ReturnStayPlanRepository plans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanAndSeedAuthorizationBoundary() {
        jdbc.update("DELETE FROM return_stay_plan_team_visibilities");
        jdbc.update("DELETE FROM return_stay_plans WHERE owner_user_id BETWEEN 923000 AND 923999");
        jdbc.update("DELETE FROM return_stay_plan_owner_locks WHERE owner_user_id BETWEEN 923000 AND 923999");
        jdbc.update("DELETE FROM user_roles WHERE user_id BETWEEN 923000 AND 923999");
        jdbc.update("DELETE FROM memberships WHERE user_id BETWEEN 923000 AND 923999");
        jdbc.update("DELETE FROM teams WHERE id BETWEEN 923000 AND 923999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 923000 AND 923999");
        insertUser(OWNER_ID, "persistence-owner@example.test", "Owner");
        insertUser(VIEWER_ID, "persistence-viewer@example.test", "Viewer");
        insertUser(SYSTEM_ADMIN_ID, "persistence-admin@example.test", "System admin");
        insertTeam(TEAM_ID, TEAM_SLUG);
        insertMembership(OWNER_ID, TEAM_ID, "MEMBER");
        insertMembership(VIEWER_ID, TEAM_ID, "MEMBER");
        insertSystemAdminRole(SYSTEM_ADMIN_ID);
    }

    @Test
    @DisplayName("AC-13 OFF retains the allow-list row in MySQL")
    void ac13_offRetainsVisibilityRows() {
        var created = service.create(OWNER_ID, request(false, TEAM_ID));
        assertThat(created.getPublished()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM return_stay_plan_team_visibilities WHERE plan_id = UUID_TO_BIN(?)",
                Long.class, created.getId().toString())).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-19 viewer and owner active MEMBER can read published plan")
    void ac19_bothMembersCanRead() {
        service.create(OWNER_ID, request(true, TEAM_ID));
        var result = service.listVisiblePlansForMembers(VIEWER_ID, TEAM_SLUG, List.of(OWNER_ID));
        assertThat(result.get(OWNER_ID)).hasSize(1);
        assertThat(result.get(OWNER_ID).getFirst().ownerDisplayName()).isEqualTo("Owner");
    }

    @Test
    @DisplayName("AC-20 SYSTEM_ADMIN without TEAM membership cannot bypass authorization")
    void ac20_systemAdminHasNoBypass() {
        service.create(OWNER_ID, request(true, TEAM_ID));
        assertThatThrownBy(() -> service.listVisiblePlansForMembers(
                SYSTEM_ADMIN_ID, TEAM_SLUG, List.of(OWNER_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ReturnStayPlanErrorCode.TEAM_ACCESS_DENIED));
    }

    @Test
    @DisplayName("AC-20 SUPPORTER cannot read TEAM plans")
    void ac20_supporterCannotRead() {
        jdbc.update("UPDATE memberships SET role_kind = 'SUPPORTER' WHERE user_id = ?", VIEWER_ID);
        service.create(OWNER_ID, request(true, TEAM_ID));
        assertThatThrownBy(() -> service.listVisiblePlansForMembers(
                VIEWER_ID, TEAM_SLUG, List.of(OWNER_ID)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-24 four hundred members use a fixed two-query authorization boundary")
    void ac24_batchAvoidsNPlusOne() {
        service.create(OWNER_ID, request(true, TEAM_ID));
        List<Long> memberIds = java.util.stream.LongStream.range(924000, 924399)
                .boxed().collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        memberIds.add(OWNER_ID);
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();

        var result = service.listVisiblePlansForMembers(VIEWER_ID, TEAM_SLUG, memberIds);

        assertThat(result).hasSize(400);
        assertThat(result.get(OWNER_ID)).hasSize(1);
        assertThat(sessionFactory.getStatistics().getQueryExecutionCount()).isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("AC-31 DB owner lock serializes two creates at the 30 row boundary")
    void ac31_ownerLockSerializesAcrossTransactions() throws InterruptedException {
        long ownerId = 923031L;
        plans.saveAllAndFlush(IntStream.range(0, 29).mapToObj(index -> plan(ownerId)).toList());
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> createAfterGate(gate, ownerId));
            Future<?> second = executor.submit(() -> createAfterGate(gate, ownerId));
            gate.countDown();
            int successes = 0;
            int limitFailures = 0;
            for (Future<?> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exception.getCause()).getErrorCode())
                            .isEqualTo(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
                    limitFailures++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(limitFailures).isEqualTo(1);
        }
        assertThat(plans.countByOwnerUserId(ownerId)).isEqualTo(30L);
    }

    @Test
    @DisplayName("AC-27 purge deletes at most 500 oldest eligible rows")
    void ac27_purgeIsOrderedAndBounded() {
        long ownerId = 923027L;
        plans.saveAllAndFlush(IntStream.range(0, 501)
                .mapToObj(index -> oldPlan(ownerId, TODAY.minusYears(1).minusDays(index + 1L)))
                .toList());
        assertThat(service.purgeExpiredPlans()).isEqualTo(500);
        assertThat(plans.countByOwnerUserId(ownerId)).isEqualTo(1L);
    }

    private void createAfterGate(CountDownLatch gate, long ownerId) {
        try {
            gate.await();
            service.create(ownerId, new ReturnStayPlanCreateRequest(
                    "HOMECOMING", false,
                    new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                    TODAY, TODAY.plusDays(3), List.of()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private ReturnStayPlanCreateRequest request(boolean published, long teamId) {
        return new ReturnStayPlanCreateRequest(
                "HOMECOMING", published,
                new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                TODAY, TODAY.plusDays(3), List.of(teamId));
    }

    private ReturnStayPlanEntity plan(long ownerId) {
        return ReturnStayPlanEntity.builder()
                .ownerUserId(ownerId)
                .planType(ReturnStayPlanEntity.PlanType.HOMECOMING)
                .published(false)
                .countryCode("JP")
                .prefectureCode("13")
                .timezone("Asia/Tokyo")
                .startDate(TODAY)
                .endDate(TODAY.plusDays(3))
                .build();
    }

    private ReturnStayPlanEntity oldPlan(long ownerId, LocalDate endDate) {
        return ReturnStayPlanEntity.builder()
                .ownerUserId(ownerId)
                .planType(ReturnStayPlanEntity.PlanType.HOMECOMING)
                .published(false)
                .countryCode("JP")
                .prefectureCode("13")
                .timezone("Asia/Tokyo")
                .startDate(endDate.minusDays(2))
                .endDate(endDate)
                .build();
    }

    private void insertUser(long id, String email, String displayName) {
        jdbc.update("""
                INSERT INTO users
                    (id, email, last_name, first_name, display_name, status,
                     is_searchable, handle_searchable, contact_approval_required,
                     online_visibility, dm_receive_from, encryption_key_version,
                     locale, timezone, reporting_restricted, follow_list_visibility,
                     care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, ?, 'Test', 'User', ?, 'ACTIVE', 1, 1, 1,
                        'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0,
                        NOW(), NOW())
                """, id, email, displayName);
    }

    private void insertTeam(long id, String slug) {
        jdbc.update("""
                INSERT INTO teams
                    (id, name, slug, visibility, supporter_enabled, member_count,
                     version, created_at, updated_at)
                VALUES (?, 'F02.11 team', ?, 'PUBLIC', 1, 0, 0, NOW(), NOW())
                """, id, slug);
    }

    private void insertMembership(long userId, long teamId, String roleKind) {
        jdbc.update("""
                INSERT INTO memberships
                    (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at)
                VALUES (?, 'TEAM', ?, ?, NOW(), NOW(), NOW())
                """, userId, teamId, roleKind);
    }

    private void insertSystemAdminRole(long userId) {
        jdbc.update("""
                INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at)
                VALUES ('SYSTEM_ADMIN', 'SYSTEM_ADMIN', 100, 1, NOW(), NOW())
                ON DUPLICATE KEY UPDATE name = VALUES(name)
                """);
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'", Long.class);
        jdbc.update("""
                INSERT INTO user_roles
                    (user_id, role_id, team_id, organization_id, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, NOW(), NOW())
                """, userId, roleId);
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock returnStayPlanPersistenceClock() {
            return Clock.fixed(Instant.parse("2026-08-17T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }
    }
}
