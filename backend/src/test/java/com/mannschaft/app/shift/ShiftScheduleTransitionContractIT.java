package com.mannschaft.app.shift;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260903-0658: F03.5 §3 の許可5遷移と拒否20遷移を実MySQLで固定する。
 *
 * <p>イベントは業務TX内の発行までを観測する。AFTER_COMMIT後の予算・Todoの実効果は
 * {@code ShiftManualArchiveConsumptionCancelIT} が別途検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260903-0658 シフト状態遷移の実DB契約")
class ShiftScheduleTransitionContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftSlotRepository slotRepository;
    @Autowired private ShiftAssignmentRunRepository runRepository;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents events;
    @PersistenceContext private EntityManager em;

    private Long teamId;
    private Long adminId;
    private Long memberId;
    private Long foreignAdminId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        teamId = insertTeam("CMP0658 チーム");
        Long otherTeamId = insertTeam("CMP0658 別チーム");
        adminId = insertUser("cmp0658-admin@example.com");
        memberId = insertUser("cmp0658-member@example.com");
        foreignAdminId = insertUser("cmp0658-foreign@example.com");
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, foreignAdminId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, foreignAdminId, "ADMIN", otherTeamId, null);
        em.flush();
        em.clear();
        setAuth(adminId);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} → {1}: 許可={2}")
    @CsvSource({
            "DRAFT,DRAFT,false", "DRAFT,COLLECTING,true", "DRAFT,ADJUSTING,false",
            "DRAFT,PUBLISHED,false", "DRAFT,ARCHIVED,false",
            "COLLECTING,DRAFT,false", "COLLECTING,COLLECTING,false", "COLLECTING,ADJUSTING,true",
            "COLLECTING,PUBLISHED,false", "COLLECTING,ARCHIVED,false",
            "ADJUSTING,DRAFT,false", "ADJUSTING,COLLECTING,true", "ADJUSTING,ADJUSTING,false",
            "ADJUSTING,PUBLISHED,true", "ADJUSTING,ARCHIVED,false",
            "PUBLISHED,DRAFT,false", "PUBLISHED,COLLECTING,false", "PUBLISHED,ADJUSTING,false",
            "PUBLISHED,PUBLISHED,false", "PUBLISHED,ARCHIVED,true",
            "ARCHIVED,DRAFT,false", "ARCHIVED,COLLECTING,false", "ARCHIVED,ADJUSTING,false",
            "ARCHIVED,PUBLISHED,false", "ARCHIVED,ARCHIVED,false"
    })
    void 全25遷移と保存副作用を固定する(ShiftScheduleStatus from, ShiftScheduleStatus to, boolean allowed)
            throws Exception {
        seedSchedule(from);
        Map<String, Object> before = scheduleSnapshot();
        Map<String, Object> requestBefore = requestSnapshot();
        var response = mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId)
                        .param("status", to.name()))
                .andReturn().getResponse();
        em.flush();
        em.clear();
        Map<String, Object> after = scheduleSnapshot();

        if (!allowed) {
            assertAll(
                    () -> assertThat(response.getStatus()).isEqualTo(409),
                    () -> assertThat(response.getContentAsString()).contains("SHIFT_012"),
                    () -> assertThat(after).as("状態・version・公開履歴・更新日時を変えない").isEqualTo(before),
                    () -> assertThat(requestSnapshot()).as("依頼を取り下げない").isEqualTo(requestBefore),
                    () -> assertThat(scheduleEventCount()).as("公開・取消・アーカイブイベントを出さない").isZero());
            return;
        }

        assertAll(
                () -> assertThat(response.getStatus()).isEqualTo(200),
                () -> assertThat(after.get("status")).isEqualTo(to.name()),
                () -> assertThat(((Number) after.get("version")).longValue())
                        .isEqualTo(((Number) before.get("version")).longValue() + 1),
                () -> assertThat(events.stream(ShiftPublishedEvent.class)
                        .filter(e -> scheduleId.equals(e.getScheduleId())).count())
                        .isEqualTo(to == ShiftScheduleStatus.PUBLISHED ? 1 : 0),
                () -> assertThat(events.stream(ShiftArchivedEvent.class)
                        .filter(e -> scheduleId.equals(e.getScheduleId())).count())
                        .isEqualTo(to == ShiftScheduleStatus.ARCHIVED ? 1 : 0),
                () -> assertThat(events.stream(ShiftScheduleClosedEvent.class)
                        .filter(e -> scheduleId.equals(e.getScheduleId())).count()).isZero());
        if (to == ShiftScheduleStatus.PUBLISHED) {
            assertThat(after.get("published_at")).isNotNull();
            assertThat(((Number) after.get("published_by")).longValue()).isEqualTo(adminId);
        } else {
            assertThat(after.get("published_at")).isEqualTo(before.get("published_at"));
            assertThat(after.get("published_by")).isEqualTo(before.get("published_by"));
        }
        if (to == ShiftScheduleStatus.ARCHIVED) {
            assertThat(requestSnapshot().get("status")).isEqualTo("WITHDRAWN");
        } else {
            assertThat(requestSnapshot()).isEqualTo(requestBefore);
        }
    }

    @Test
    void 公開可能な遷移でも未目視の割当があれば409で不変() throws Exception {
        seedSchedule(ShiftScheduleStatus.ADJUSTING);
        runRepository.saveAndFlush(ShiftAssignmentRunEntity.builder()
                .scheduleId(scheduleId).triggeredBy(adminId).status(ShiftAssignmentRunStatus.SUCCEEDED).build());
        Map<String, Object> before = scheduleSnapshot();
        Map<String, Object> requestBefore = requestSnapshot();
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId).param("status", "PUBLISHED"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_025"));
        assertUnchanged(before, requestBefore);
    }

    @Test
    void 同一チームの非管理者は許可された遷移も403で不変() throws Exception {
        seedSchedule(ShiftScheduleStatus.DRAFT);
        Map<String, Object> before = scheduleSnapshot();
        Map<String, Object> requestBefore = requestSnapshot();
        setAuth(memberId);
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId).param("status", "COLLECTING"))
                .andExpect(status().isForbidden());
        assertUnchanged(before, requestBefore);
    }

    @Test
    void 別チームの管理者は許可された遷移も404で不変() throws Exception {
        seedSchedule(ShiftScheduleStatus.DRAFT);
        Map<String, Object> before = scheduleSnapshot();
        Map<String, Object> requestBefore = requestSnapshot();
        setAuth(foreignAdminId);
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId).param("status", "COLLECTING"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SHIFT_001"));
        assertUnchanged(before, requestBefore);
    }

    private void assertUnchanged(Map<String, Object> before, Map<String, Object> requestBefore) {
        em.flush();
        em.clear();
        assertAll(() -> assertThat(scheduleSnapshot()).isEqualTo(before),
                () -> assertThat(requestSnapshot()).isEqualTo(requestBefore),
                () -> assertThat(scheduleEventCount()).isZero());
    }

    private void seedSchedule(ShiftScheduleStatus state) {
        boolean published = state == ShiftScheduleStatus.PUBLISHED || state == ShiftScheduleStatus.ARCHIVED;
        var schedule = scheduleRepository.saveAndFlush(ShiftScheduleEntity.builder()
                .teamId(teamId).title("CMP0658 シフト").periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1)).endDate(LocalDate.of(2026, 3, 7))
                .status(state).createdBy(adminId)
                .publishedAt(published ? LocalDateTime.of(2026, 2, 20, 10, 0) : null)
                .publishedBy(published ? adminId : null).build());
        scheduleId = schedule.getId();
        var slot = slotRepository.saveAndFlush(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.of(2026, 3, 1))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build());
        em.createNativeQuery("INSERT INTO shift_change_requests "
                        + "(schedule_id, slot_id, request_type, status, requested_by, reason, version, created_at, updated_at) "
                        + "VALUES (:schedule, :slot, 'INDIVIDUAL_SWAP', 'OPEN', :actor, 'CMP0658 変更依頼', 0, NOW(), NOW())")
                .setParameter("schedule", scheduleId).setParameter("slot", slot.getId())
                .setParameter("actor", adminId).executeUpdate();
        em.flush();
        em.clear();
    }

    private Map<String, Object> scheduleSnapshot() {
        return jdbcTemplate.queryForMap("SELECT status, version, published_at, published_by, updated_at "
                + "FROM shift_schedules WHERE id = ?", scheduleId);
    }

    private Map<String, Object> requestSnapshot() {
        return jdbcTemplate.queryForMap("SELECT status, version, updated_at FROM shift_change_requests "
                + "WHERE schedule_id = ?", scheduleId);
    }

    private long scheduleEventCount() {
        return events.stream(ShiftPublishedEvent.class).filter(e -> scheduleId.equals(e.getScheduleId())).count()
                + events.stream(ShiftArchivedEvent.class).filter(e -> scheduleId.equals(e.getScheduleId())).count()
                + events.stream(ShiftScheduleClosedEvent.class).filter(e -> scheduleId.equals(e.getScheduleId())).count();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertTeam(String name) {
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, dm_receive_from, "
                        + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'CMP0658', 'テスト', 'CMP0658 テスト', 'ACTIVE', "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }
}
