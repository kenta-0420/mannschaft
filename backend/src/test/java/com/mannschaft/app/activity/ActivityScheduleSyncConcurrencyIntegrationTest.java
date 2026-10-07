package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** testTXを持たず、独立HTTPトランザクションの競合と途中失敗のcommit後状態を検証する。 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityScheduleSyncConcurrencyIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long AUTHOR = 940200101L;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ActivityResultRepository activities;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate tx;
    private Long teamId;
    private Long scheduleId;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            MembershipTestHelper.insertActiveUser(em, AUTHOR);
            em.createNativeQuery("INSERT INTO teams(name,visibility,supporter_enabled,version,member_count,slug,created_at,updated_at) VALUES('同期競合試練','PUBLIC',1,0,0,CONCAT('sync-c-',LEFT(REPLACE(UUID(),'-',''),8)),NOW(),NOW())").executeUpdate();
            teamId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
            MembershipTestHelper.insertMembership(em, AUTHOR, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, AUTHOR, "ADMIN", teamId, null);
            scheduleId = schedules.saveAndFlush(ScheduleEntity.builder().teamId(teamId).title("予定由来")
                    .startAt(LocalDateTime.of(2026, 10, 10, 23, 0)).endAt(LocalDateTime.of(2026, 10, 11, 1, 0))
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED).createdBy(AUTHOR).build()).getId();
        });
    }

    @AfterEach
    void cleanup() {
        if (teamId == null) return;
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM activity_participants WHERE activity_result_id IN (SELECT id FROM activity_results WHERE schedule_id=:id)").setParameter("id", scheduleId).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_results WHERE schedule_id=:id").setParameter("id", scheduleId).executeUpdate();
            em.createNativeQuery("DELETE FROM schedules WHERE id=:id").setParameter("id", scheduleId).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type='TEAM' AND scope_id=:id").setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id=:id").setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id=:id").setParameter("id", teamId).executeUpdate();
        });
    }

    @Test
    void 同時作成は独立TXでも一件だけcommitする() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return create(); });
            var second = pool.submit(() -> { start.await(); return create(); });
            start.countDown();
            long id = first.get(30, TimeUnit.SECONDS).path("id").asLong();
            assertThat(second.get(30, TimeUnit.SECONDS).path("id").asLong()).isEqualTo(id);
            Integer count = tx.execute(status -> activities.findAllByScheduleIdOrderByIdAsc(scheduleId).size());
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void 予定保存後の同期検証失敗は両方rollbackする() throws Exception {
        JsonNode activity = create();
        Map<String, Object> update = Map.of("title", "保存されない予定", "startAt", "2026-10-12T23:00:00+09:00", "endAt", "2026-10-13T01:00:00+09:00");
        var previewResult = mvc.perform(post("/api/v1/teams/{team}/schedules/{schedule}/activity-sync-preview", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleUpdate", update, "updateScope", "THIS_ONLY"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode preview = json.readTree(previewResult.getResponse().getContentAsString()).path("data");
        var body = new java.util.HashMap<>(update);
        // 終了日を選ばないと開始日だけが終了日を越える。apply中の検証失敗を作る。
        body.put("syncConfirmation", Map.of("expectedScheduleState", preview.get("expectedScheduleState"), "activities",
                List.of(Map.of("id", activity.path("id").asLong(), "version", activity.path("version").asLong(), "applyFields", List.of("activityDate")))));
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        String title = tx.execute(status -> schedules.findById(scheduleId).orElseThrow().getTitle());
        LocalDate date = tx.execute(status -> activities.findById(activity.path("id").asLong()).orElseThrow().getActivityDate());
        assertThat(title).isEqualTo("予定由来");
        assertThat(date).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    private JsonNode create() throws Exception {
        var result = mvc.perform(post("/api/v1/activities/draft-from-schedule").with(user(Long.toString(AUTHOR)))
                .param("scope_type", "TEAM").param("scope_id", teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleId", scheduleId)))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @Test
    void 参加者変更はversionを進め古い同期確認を拒否する() throws Exception {
        JsonNode activity = create();
        var update = Map.<String, Object>of("title", "競合予定");
        var result = mvc.perform(post("/api/v1/teams/{team}/schedules/{schedule}/activity-sync-preview", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleUpdate", update))))
                .andExpect(status().isOk()).andReturn();
        var preview = json.readTree(result.getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/v1/activities/{id}/participants", activity.path("id").asLong())
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userIds", List.of(AUTHOR)))))
                .andExpect(status().isOk());
        var body = new java.util.HashMap<>(update);
        body.put("syncConfirmation", Map.of("expectedScheduleState", preview.get("expectedScheduleState"), "activities",
                List.of(Map.of("id", activity.path("id").asLong(), "version", activity.path("version").asLong(), "applyFields", List.of("title")))));
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict());
        Long version = tx.execute(status -> activities.findById(activity.path("id").asLong()).orElseThrow().getVersion());
        assertThat(version).isGreaterThan(activity.path("version").asLong());
    }
}
