package com.mannschaft.app.returnstayplan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

/** F02.11 validation and state contracts against the real MySQL repositories. */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(ReturnStayPlanServiceTest.FixedClockConfiguration.class)
class ReturnStayPlanServiceTest extends AbstractMySqlIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);
    private static final long OWNER_ID = 921001L;
    private static final long TEAM_ID = 921002L;

    @Autowired
    private ReturnStayPlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndSeed() {
        jdbc.update("DELETE FROM return_stay_plan_team_visibilities");
        jdbc.update("DELETE FROM return_stay_plans WHERE owner_user_id >= 921000");
        jdbc.update("DELETE FROM return_stay_plan_owner_locks WHERE owner_user_id >= 921000");
        jdbc.update("DELETE FROM memberships WHERE user_id >= 921000");
        jdbc.update("DELETE FROM teams WHERE id >= 921000");
        jdbc.update("DELETE FROM users WHERE id >= 921000");
        insertUser(OWNER_ID, "owner-f0211@example.test", "Owner");
        insertTeam(TEAM_ID, "f0211-service-team");
        insertMembership(OWNER_ID, TEAM_ID, "MEMBER");
    }

    @Test
    @DisplayName("AC-03 create persists input, UUIDv7, Asia/Tokyo and allow-list")
    void ac03_createPersistsCompleteContract() {
        var created = service.create(OWNER_ID, request(
                "HOMECOMING", true, "JP", "13", null,
                TODAY, TODAY.plusDays(3), List.of(TEAM_ID)));

        assertThat(created.getId().version()).isEqualTo(7);
        assertThat(created.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(created.getTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM return_stay_plan_team_visibilities WHERE plan_id = ?",
                Long.class, created.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-06 startDate before ownerToday is INVALID_REQUEST")
    void ac06_rejectsPastStartDate() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("HOMECOMING", false, "JP", "01", null,
                        TODAY.minusDays(1), TODAY.plusDays(1), List.of())));
    }

    @Test
    @DisplayName("AC-07 ownerToday plus 365 days is accepted")
    void ac07_acceptsEndDateAt365Days() {
        var created = service.create(OWNER_ID, request("STAYING", false, "JP", "47", null,
                TODAY.plusDays(1), TODAY.plusDays(365), List.of()));
        assertThat(created.getEndDate()).isEqualTo(TODAY.plusDays(365));
    }

    @Test
    @DisplayName("AC-07 ownerToday plus 366 days is INVALID_REQUEST")
    void ac07_rejectsEndDateAt366Days() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("STAYING", false, "JP", "47", null,
                        TODAY.plusDays(1), TODAY.plusDays(366), List.of())));
    }

    @Test
    @DisplayName("AC-08 invalid prefecture code is rejected")
    void ac08_rejectsInvalidPrefecture() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("HOMECOMING", false, "JP", "00", null,
                        TODAY, TODAY.plusDays(2), List.of())));
    }

    @Test
    @DisplayName("AC-09 JP regionName is rejected")
    void ac09_rejectsJpRegionName() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("STAYING", false, "JP", "27", "Osaka",
                        TODAY, TODAY.plusDays(2), List.of())));
    }

    @Test
    @DisplayName("AC-10 overseas location is rejected before rollout")
    void ac10_rejectsOverseasBeforeRollout() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("STAYING", false, "US", null, "California",
                        TODAY, TODAY.plusDays(2), List.of())));
    }

    @Test
    @DisplayName("AC-11 published plan requires at least one team")
    void ac11_rejectsPublishedWithoutTeam() {
        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, request("HOMECOMING", true, "JP", "13", null,
                        TODAY, TODAY.plusDays(2), List.of())));
    }

    @Test
    @DisplayName("AC-12 more than twenty teams exceeds limit")
    void ac12_rejectsTwentyOneTeams() {
        var ids = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();
        assertBusinessError(ReturnStayPlanErrorCode.LIMIT_EXCEEDED,
                () -> service.create(OWNER_ID, request("HOMECOMING", true, "JP", "13", null,
                        TODAY, TODAY.plusDays(2), ids)));
    }

    @Test
    @DisplayName("AC-15 status uses owner timezone and inclusive endpoints")
    void ac15_resolvesAllStatuses() {
        assertThat(service.resolveStatus(TODAY.plusDays(1), TODAY.plusDays(2), "Asia/Tokyo"))
                .isEqualTo(ReturnStayPlanService.DisplayStatus.UPCOMING);
        assertThat(service.resolveStatus(TODAY, TODAY, "Asia/Tokyo"))
                .isEqualTo(ReturnStayPlanService.DisplayStatus.ACTIVE);
        assertThat(service.resolveStatus(TODAY.minusDays(2), TODAY.minusDays(1), "Asia/Tokyo"))
                .isEqualTo(ReturnStayPlanService.DisplayStatus.ENDED);
    }

    private void assertBusinessError(
            ReturnStayPlanErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(expected));
    }

    private ReturnStayPlanCreateRequest request(
            String planType, boolean published, String countryCode, String prefectureCode,
            String regionName, LocalDate startDate, LocalDate endDate, List<Long> teamIds) {
        return new ReturnStayPlanCreateRequest(planType, published,
                new ReturnStayPlanCreateRequest.Location(countryCode, prefectureCode, regionName),
                startDate, endDate, teamIds);
    }

    private void insertUser(long id, String email, String displayName) {
        jdbc.update("""
                INSERT INTO users
                    (id, email, last_name, first_name, display_name, status,
                     is_searchable, handle_searchable, contact_approval_required,
                     online_visibility, dm_receive_from, encryption_key_version,
                     locale, timezone, reporting_restricted, follow_list_visibility,
                     care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, ?, 'Test', 'User', ?, 'ACTIVE',
                        1, 1, 1, 'NOBODY', 'ANYONE', 1,
                        'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
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

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock returnStayPlanTestClock() {
            return Clock.fixed(Instant.parse("2026-08-17T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
        }
    }
}
