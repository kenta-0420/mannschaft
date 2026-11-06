package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261008-1203の予定保存・メタデータ閲覧・削除を実HTTP境界と実MySQLで先行固定する。
 * テストTXを設けず、予定POSTの実commit後に別TXで活動の永続状態を読み直す。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityAutomaticScheduleIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long AUTHOR = 940208101L;
    private static final long MEMBER = 940208102L;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TeamRepository teams;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ActivityResultRepository activities;
    @Autowired private com.mannschaft.app.activity.service.AutomaticScheduleActivityService generation;
    @Autowired private com.mannschaft.app.schedule.service.ScheduleActivitySourceService sources;
    @Autowired private com.mannschaft.app.activity.service.ActivityResultService activityService;
    @Autowired private com.mannschaft.app.activity.service.AutomaticActivityListService automaticLists;
    @Autowired private com.mannschaft.app.common.visibility.ContentVisibilityChecker visibilityChecker;
    @Autowired private com.mannschaft.app.common.AccessControlService accessControl;
    @Autowired private com.mannschaft.app.common.activityschedule.AutomaticScheduleCompletionFacade completion;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate tx;
    private Long teamId;


    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(transaction -> {
            MembershipTestHelper.insertActiveUser(em, AUTHOR);
            MembershipTestHelper.insertActiveUser(em, MEMBER);
            teamId = teams.saveAndFlush(TeamEntity.builder().name("自動活動試練")
                    .slug("auto-" + UUID.randomUUID().toString().substring(0, 12)).visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true).build()).getId();
            MembershipTestHelper.insertMembership(em, AUTHOR, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, AUTHOR, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, MEMBER, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        });
    }

    @AfterEach
    void cleanup() {
        if (teamId == null) return;
        tx.executeWithoutResult(transaction -> {
            em.createNativeQuery("DELETE FROM activity_comments WHERE activity_result_id IN (SELECT id FROM activity_results WHERE scope_type='TEAM' AND scope_id=:id)")
                    .setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_participants WHERE activity_result_id IN (SELECT id FROM activity_results WHERE scope_type='TEAM' AND scope_id=:id)")
                    .setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_results WHERE scope_type='TEAM' AND scope_id=:id")
                    .setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM schedules WHERE team_id=:id").setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type='TEAM' AND scope_id=:id").setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id=:id").setParameter("id", teamId).executeUpdate();
            teams.deleteById(teamId);
        });
    }

    @Test
    void 個人予定は認証主体に保存され共有予定と異なり活動を生成しない() throws Exception {
        var response = mvc.perform(post("/api/v1/me/schedules").with(user(Long.toString(AUTHOR)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "活動対象外の個人予定",
                                "startAt", "2026-10-15T23:00:12+09:00",
                                "endAt", "2026-10-16T01:00:34+09:00", "allDay", false))))
                .andExpect(status().isCreated()).andReturn();
        long personalId = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertThat(personalId).isPositive();
        try {
            // HTTP保存TXのcommit後、別TXで認証主体と実保存先を読み直す。
            tx.executeWithoutResult(t -> {
                var saved = schedules.findById(personalId).orElseThrow();
                assertThat(saved.getId()).isEqualTo(personalId);
                assertThat(saved.getUserId()).isEqualTo(AUTHOR);
                assertThat(saved.getCreatedBy()).isEqualTo(AUTHOR);
                assertThat(saved.getTeamId()).isNull();
                assertThat(saved.getOrganizationId()).isNull();
                assertThat(saved.getTitle()).isEqualTo("活動対象外の個人予定");
                assertThat(saved.getStartAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 15, 23, 0, 12));
                assertThat(saved.getEndAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 16, 1, 0, 34));
                Number linked = (Number) em.createNativeQuery("SELECT COUNT(*) FROM activity_results WHERE schedule_id=:id")
                        .setParameter("id", personalId).getSingleResult();
                assertThat(linked.longValue()).isZero();
            });
            // 共有予定の既存生成も同じ認証主体で維持される。
            long sharedId = activityId(createSchedule(Map.of()));
            assertThat(detail(sharedId, AUTHOR).path("autoGeneratedFromSchedule").asBoolean()).isTrue();
        } finally {
            tx.executeWithoutResult(t -> em.createNativeQuery("DELETE FROM schedules WHERE id=:id AND user_id=:user")
                    .setParameter("id", personalId).setParameter("user", AUTHOR).executeUpdate());
        }
    }

    @Test
    void 完了でversionが進むと古い編集と同期確認は409で再preview保存できる() throws Exception {
        long scheduleId = createSchedule(Map.of("startAt", "2026-01-01T23:00:12+09:00", "endAt", "2026-01-02T01:00:34+09:00"));
        long id = activityId(scheduleId);
        var before = detail(id, AUTHOR);
        var preview = syncPreview(scheduleId, "完了後予定修正");
        assertThat(completion.completeOne(scheduleId, java.time.OffsetDateTime.now(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE))).isTrue();
        var completed = detail(id, AUTHOR);
        assertThat(completed.path("version").asLong()).isEqualTo(before.path("version").asLong() + 1);
        mvc.perform(put("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "古い版更新",
                        "activityDate", "2026-01-01", "version", before.path("version").asLong()))))
                .andExpect(status().isConflict());
        confirmSync(scheduleId, id, preview, "完了後予定修正").andExpect(status().isConflict());
        String unchanged = tx.execute(t -> schedules.findById(scheduleId).orElseThrow().getTitle());
        assertThat(unchanged).isEqualTo("自動予定");
        confirmSync(scheduleId, id, syncPreview(scheduleId, "完了後予定修正"), "完了後予定修正")
                .andExpect(status().isOk());
        assertThat(detail(id, AUTHOR).path("title").asText()).isEqualTo("完了後予定修正");
        assertThat(detail(id, AUTHOR).path("isPlanned").asBoolean()).isFalse();
    }

    @Test
    void 過去終了の予定も保存直後は予定で次の期限処理だけが完了へ変える() throws Exception {
        long scheduleId = createSchedule(Map.of("startAt", "2000-01-01T23:00:12+09:00",
                "endAt", "2000-01-02T01:00:34+09:00"));
        long id = activityId(scheduleId);
        var before = detail(id, AUTHOR);
        assertThat(before.path("status").asText()).isEqualTo("DRAFT");
        assertThat(before.path("autoGeneratedFromSchedule").asBoolean()).isTrue();
        assertThat(before.path("isPlanned").asBoolean()).isTrue();
        var beforeStatus = tx.execute(t -> schedules.findById(scheduleId).orElseThrow().getStatus());
        assertThat(beforeStatus).isEqualTo(com.mannschaft.app.schedule.ScheduleStatus.SCHEDULED);
        // batchと同じ行処理を固定nowで呼び、保存TXには完了処理が混ざらないことを確認する。
        assertThat(completion.completeOne(scheduleId, java.time.OffsetDateTime.parse("2000-01-02T01:00:35+09:00"))).isTrue();
        var after = detail(id, AUTHOR);
        assertThat(after.path("isPlanned").asBoolean()).isFalse();
        assertThat(after.path("status").asText()).isEqualTo("DRAFT");
        assertThat(activityId(scheduleId)).isEqualTo(id);
        var afterStatus = tx.execute(t -> schedules.findById(scheduleId).orElseThrow().getStatus());
        assertThat(afterStatus).isEqualTo(com.mannschaft.app.schedule.ScheduleStatus.COMPLETED);
    }

    private JsonNode syncPreview(long scheduleId, String title) throws Exception {
        var response = mvc.perform(post("/api/v1/teams/{team}/schedules/{id}/activity-sync-preview", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleUpdate", Map.of("title", title)))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private org.springframework.test.web.servlet.ResultActions confirmSync(long scheduleId, long id,
            JsonNode preview, String title) throws Exception {
        return mvc.perform(patch("/api/v1/teams/{team}/schedules/{id}", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title, "syncConfirmation", Map.of(
                        "expectedScheduleState", preview.path("expectedScheduleState"), "activities", List.of(Map.of(
                        "id", id, "version", preview.path("activities").get(0).path("version").asLong(), "applyFields", List.of("title"))))))));
    }

    @Test
    void 正当に公開実績を読める所属者の複製は手動記録として維持する() throws Exception {
        long id = activityId(createSchedule(Map.of()));
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE activity_results SET status='PUBLISHED',is_planned=FALSE,description='公開済み実績本文' WHERE id=:id")
                .setParameter("id", id).executeUpdate());
        var response = mvc.perform(post("/api/v1/activities/{id}/duplicate", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isCreated()).andReturn();
        var copy = json.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(copy.path("description").asText()).isEqualTo("公開済み実績本文");
        assertThat(copy.path("autoGeneratedFromSchedule").asBoolean()).isFalse();
        assertThat(copy.path("isPlanned").asBoolean()).isFalse();
        assertThat(copy.path("scheduleId").isNull()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN"})
    void 非作者の在籍管理者はauto実績のactualを読める(String role) throws Exception {
        long id = activityId(createSchedule(Map.of("minViewRole", "ADMIN_ONLY")));
        tx.executeWithoutResult(t -> {
            MembershipTestHelper.insertUserRole(em, MEMBER, role, teamId, null);
            em.createNativeQuery("UPDATE activity_results SET description='管理者が扱う保存実績本文' WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
        });
        var full = detail(id, MEMBER);
        assertThat(full.path("metadataOnly").asBoolean()).isFalse();
        assertThat(full.path("description").asText()).isEqualTo("管理者が扱う保存実績本文");
        assertThat(full.path("canEdit").asBoolean()).isTrue();
        assertThat(full.path("canDelete").asBoolean()).isTrue();
    }

    @Test
    void 公開後も非可視sourceの未公開実績をコメント複製別APIへ出さない() throws Exception {
        long id = activityId(createSchedule(Map.of("minViewRole", "ADMIN_ONLY")));
        mvc.perform(post("/api/v1/activities/{id}/comments", id).with(user(Long.toString(AUTHOR)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"source制限内コメント\"}"))
                .andExpect(status().isCreated());
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE activity_results SET status='PUBLISHED',visibility='PUBLIC',is_planned=FALSE,description='source制限内実績本文' WHERE id=:id")
                .setParameter("id", id).executeUpdate());
        mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/activities/{id}/comments", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/comments", id).with(user(Long.toString(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"不正追記\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/duplicate", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非所属SYSTEM_ADMINでもautoのactual読取と削除は昇格しない() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM memberships WHERE user_id=:user AND scope_type='TEAM' AND scope_id=:id")
                    .setParameter("user", MEMBER).setParameter("id", teamId).executeUpdate();
            MembershipTestHelper.insertUserRole(em, MEMBER, "SYSTEM_ADMIN", null, null);
            em.createNativeQuery("UPDATE activity_results SET status='PUBLISHED',is_planned=FALSE WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
        });
        try {
            assertThat(accessControl.isSystemAdmin(MEMBER)).isTrue();
            assertThat(visibilityChecker.canView(com.mannschaft.app.common.visibility.ReferenceType.ACTIVITY_RESULT, id, MEMBER)).isFalse();
            mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER))))
                    .andExpect(status().isForbidden());
            assertThat(activityId(scheduleId)).isEqualTo(id);
        } finally {
            tx.executeWithoutResult(t -> em.createNativeQuery("DELETE FROM user_roles WHERE user_id=:user AND team_id IS NULL AND organization_id IS NULL")
                    .setParameter("user", MEMBER).executeUpdate());
        }
    }

    @Test
    void 生存元予定の閲覧権を失っても作者は保存実績を編集でき秘密予定を参照しない() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("UPDATE activity_results SET description='保存済み実績本文' WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id=:user AND team_id=:team")
                    .setParameter("user", AUTHOR).setParameter("team", teamId).executeUpdate();
            em.createNativeQuery("UPDATE schedules SET min_view_role='ADMIN_ONLY',title='閲覧不可の新秘密予定', "
                    + "start_at='2027-02-01 09:17:23',end_at='2027-02-02 10:19:29',target_mode='SELECTED_MEMBERS' WHERE id=:id")
                    .setParameter("id", scheduleId).executeUpdate();
        });
        // 共有予定はPUBLISHED相当で作者短絡しない。在籍作者でも変更後の閾値には届かない。
        assertThat(visibilityChecker.canView(com.mannschaft.app.common.visibility.ReferenceType.SCHEDULE, scheduleId, AUTHOR)).isFalse();
        Boolean sourceRetained = tx.execute(t -> schedules.findById(scheduleId).isPresent());
        assertThat(sourceRetained).isTrue();
        var saved = detail(id, AUTHOR);
        assertThat(saved.path("metadataOnly").asBoolean()).isFalse();
        assertThat(saved.path("canEdit").asBoolean()).isTrue();
        assertThat(saved.path("title").asText()).isEqualTo("自動予定");
        assertThat(saved.path("activityDate").asText()).isEqualTo("2026-10-15");
        assertThat(saved.path("description").asText()).isEqualTo("保存済み実績本文");
        assertThat(saved.path("sourceSchedule").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(saved.path("sourceSchedule").path("canView").asBoolean()).isFalse();
        for (String key : List.of("scopeType", "scopeId", "scopePublicId", "id")) {
            assertThat(saved.path("sourceSchedule").path(key).isNull()).as(key).isTrue();
        }
        assertThat(saved.toString()).doesNotContain("閲覧不可の新秘密予定", "2027-02-01", "2027-02-02", "SELECTED_MEMBERS");
        mvc.perform(put("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "title", "保存実績の追記", "activityDate", "2026-10-15", "activityEndDate", "2026-10-16",
                        "description", "閲覧権喪失後の実績追記", "version", saved.path("version").asLong()))))
                .andExpect(status().isOk());
        var updated = detail(id, AUTHOR);
        assertThat(updated.path("title").asText()).isEqualTo("保存実績の追記");
        assertThat(updated.path("description").asText()).isEqualTo("閲覧権喪失後の実績追記");
        assertThat(updated.toString()).doesNotContain("閲覧不可の新秘密予定", "2027-02-01", "2027-02-02", "SELECTED_MEMBERS");
        assertThat(visibilityChecker.canView(com.mannschaft.app.common.visibility.ReferenceType.SCHEDULE, scheduleId, AUTHOR)).isFalse();
    }

    @Test
    void 予定を中止しても活動を保持し元予定の中止状態を返す() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        mvc.perform(post("/api/v1/teams/{team}/schedules/{id}/cancel", teamId, scheduleId)
                .with(user(Long.toString(AUTHOR))))
                .andExpect(status().isNoContent());
        tx.executeWithoutResult(t -> {
            var schedule = schedules.findById(scheduleId).orElseThrow();
            assertThat(schedule.getStatus()).isEqualTo(com.mannschaft.app.schedule.ScheduleStatus.CANCELLED);
            var retained = activities.findById(id).orElseThrow();
            assertThat(retained.getScheduleId()).isEqualTo(scheduleId);
            assertThat(retained.isPlanned()).isTrue();
            assertThat(retained.getStatus()).isEqualTo(ActivityStatus.DRAFT);
        });
        for (Long viewer : List.of(AUTHOR, MEMBER)) {
            var saved = detail(id, viewer);
            assertThat(saved.path("sourceSchedule").path("state").asText()).isEqualTo("CANCELLED");
            assertThat(saved.path("sourceSchedule").path("canView").asBoolean()).isTrue();
            assertThat(saved.path("sourceSchedule").path("id").asLong()).isEqualTo(scheduleId);
            assertThat(saved.path("isPlanned").asBoolean()).isTrue();
        }
    }

    @Test
    void 元予定が論理削除済みでも作者の実績保持と明示削除は許可する() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE schedules SET deleted_at=NOW() WHERE id=:id")
                .setParameter("id", scheduleId).executeUpdate());
        var saved = detail(id, AUTHOR);
        assertThat(saved.path("metadataOnly").asBoolean()).isFalse();
        assertThat(saved.path("sourceSchedule").path("state").asText()).isEqualTo("UNAVAILABLE");
        mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR))))
                .andExpect(status().isNoContent());
        Long retained = tx.execute(t -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM schedules WHERE id=:id AND deleted_at IS NOT NULL")
                .setParameter("id", scheduleId).getSingleResult()).longValue());
        assertThat(retained).isEqualTo(1);
    }

    @Test
    void metadata閲覧は更新公開参加者複製のactual権限へ昇格しない() throws Exception {
        long id = activityId(createSchedule(Map.of()));
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE activity_results SET description='複製にも出さない実績本文' WHERE id=:id")
                .setParameter("id", id).executeUpdate());
        mvc.perform(put("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"不正更新\",\"activityDate\":\"2026-10-15\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/publish", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/participants", id).with(user(Long.toString(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userIds", List.of(MEMBER)))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/participants", id).with(user(Long.toString(AUTHOR)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userIds", List.of(AUTHOR)))))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/activities/{id}/participants", id).with(user(Long.toString(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userIds", List.of(AUTHOR)))))
                .andExpect(status().isNotFound());
        assertThat(detail(id, AUTHOR).path("participants").size()).isEqualTo(1);
        mvc.perform(post("/api/v1/activities/{id}/duplicate", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
    }

    @Test
    void 二十千一万件でもsource認可はbulkでpageとtotalが一致する() {
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        // 共通Spring contextを分岐させず、既存SQL loggerの展開済みplaceholder数を観測する。
        var sqlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        var oldLevel = sqlLogger.getLevel();
        boolean oldAdditive = sqlLogger.isAdditive();
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();
        sqlLogger.addAppender(capture);
        sqlLogger.setAdditive(false);
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        // 実依存JdbcBindingLoggingのint parameter indexだけを読み、値を証跡へ出さない。
        var bindLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind");
        var oldBindLevel = bindLogger.getLevel();
        boolean oldBindAdditive = bindLogger.isAdditive();
        var bindCapture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        bindCapture.start();
        bindLogger.addAppender(bindCapture);
        bindLogger.setAdditive(false);
        bindLogger.setLevel(ch.qos.logback.classic.Level.OFF);
        var parameterIndex = java.util.regex.Pattern.compile("^binding parameter \\((\\d+):");
        int inserted = 0;
        try {
            for (int count : List.of(20, 1000, 10000)) {
                int from = inserted;
                tx.executeWithoutResult(t -> {
                    for (int i = from; i < count; i++) {
                        var schedule = com.mannschaft.app.schedule.entity.ScheduleEntity.builder()
                                .title("性能予定" + i).teamId(teamId).createdBy(AUTHOR)
                                .status(com.mannschaft.app.schedule.ScheduleStatus.SCHEDULED)
                                .eventType(com.mannschaft.app.schedule.EventType.PRACTICE)
                                .visibility(com.mannschaft.app.schedule.ScheduleVisibility.MEMBERS_ONLY)
                                .minViewRole(i % 2 == 0 ? com.mannschaft.app.schedule.MinViewRole.MEMBER_PLUS
                                        : com.mannschaft.app.schedule.MinViewRole.ADMIN_ONLY)
                                .startAt(java.time.LocalDateTime.of(2026, 10, 15, 12, 0))
                                .endAt(java.time.LocalDateTime.of(2026, 10, 15, 13, 0)).build();
                        em.persist(schedule);
                        em.persist(com.mannschaft.app.activity.entity.ActivityResultEntity.builder()
                                .scopeType(ActivityScopeType.TEAM).scopeId(teamId).scheduleId(schedule.getId())
                                .createdBy(AUTHOR).autoGeneratedFromSchedule(true).planned(true)
                                .title("実績非公開" + i).activityDate(java.time.LocalDate.of(2026, 10, 15))
                                .status(ActivityStatus.DRAFT).visibility(ActivityVisibility.MEMBERS_ONLY).build());
                        if (i % 200 == 199) { em.flush(); em.clear(); }
                    }
                });
                inserted = count;
                statistics.clear();
                capture.list.clear();
                bindCapture.list.clear();
                bindLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
                long start = System.nanoTime();
                var page = automaticLists.list(MEMBER, ActivityScopeType.TEAM, teamId, null,
                        org.springframework.data.domain.PageRequest.of(1, 3));
                bindLogger.setLevel(ch.qos.logback.classic.Level.OFF);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                long statements = statistics.getPrepareStatementCount();
                int maximumPlaceholders = capture.list.stream()
                        .filter(event -> event.getThreadName().equals(Thread.currentThread().getName()))
                        .mapToInt(event -> (int) event.getFormattedMessage().chars().filter(c -> c == '?').count()).max().orElse(0);
                var boundIndexes = bindCapture.list.stream()
                        .filter(event -> event.getThreadName().equals(Thread.currentThread().getName()))
                        .map(event -> parameterIndex.matcher(event.getFormattedMessage()))
                        .filter(java.util.regex.Matcher::find)
                        .map(matcher -> Integer.parseInt(matcher.group(1))).toList();
                int maximumBindIndex = boundIndexes.stream().mapToInt(Integer::intValue).max().orElse(0);
                assertThat(maximumBindIndex).as("Hibernate JDBC binderの実parameter index最大値").isGreaterThanOrEqualTo(count);
                assertThat(maximumBindIndex).isLessThan(65535);
                assertThat(boundIndexes.size()).isGreaterThanOrEqualTo(maximumBindIndex);
                assertThat(page.getTotalElements()).isEqualTo(count / 2);
                assertThat(page.getContent()).hasSize(3).allSatisfy(row -> {
                    assertThat(row.isMetadataOnly()).isTrue();
                    assertThat(row.getTitle()).startsWith("性能予定");
                    assertThat(row.getDescription()).isNull();
                });
                assertThat(statements).as("件数依存のSQL発行を禁止").isLessThanOrEqualTo(25);
                assertThat(maximumPlaceholders).as("展開済みsource ID集合のSQLを実際に観測する").isGreaterThanOrEqualTo(count);
                assertThat(maximumPlaceholders).isLessThan(65535);
                System.out.printf("AUTO_PAGE_PERF candidates=%d visible=%d statements=%d elapsedMs=%d maxSqlPlaceholders=%d maxJdbcBindIndex=%d jdbcBindEvents=%d page=1 size=3%n",
                        count, page.getTotalElements(), statements, elapsedMillis, maximumPlaceholders, maximumBindIndex, boundIndexes.size());
            }
        } finally {
            bindLogger.detachAppender(bindCapture);
            bindCapture.stop();
            bindLogger.setLevel(oldBindLevel);
            bindLogger.setAdditive(oldBindAdditive);
            sqlLogger.detachAppender(capture);
            capture.stop();
            sqlLogger.setLevel(oldLevel);
            sqlLogger.setAdditive(oldAdditive);
            statistics.setStatisticsEnabled(enabled);
        }
    }

    @Test
    void 公開済みautoでも非公開予定の件数とsitemapを匿名へ出さない() throws Exception {
        long hidden = activityId(createSchedule(Map.of()));
        long manual = activityId(createSchedule(Map.of()));
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("UPDATE activity_results SET status='PUBLISHED',visibility='PUBLIC',is_planned=FALSE WHERE id IN (:hidden,:manual)")
                    .setParameter("hidden", hidden).setParameter("manual", manual).executeUpdate();
            em.createNativeQuery("UPDATE activity_results SET is_auto_generated_from_schedule=FALSE WHERE id=:id")
                    .setParameter("id", manual).executeUpdate();
        });
        var page = activityService.listPublicActivities(ActivityScopeType.TEAM, teamId,
                org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(a -> a.getId()).containsExactly(manual);
        assertThat(activityService.findPublicActivitiesForSitemap(List.of(teamId), List.of()))
                .extracting(row -> row.activityId()).containsExactly(manual);
        // ScheduleVisibilityにはPUBLICが存在しない。manual公開を正当な匿名閲覧の対照にする。
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE schedules SET deleted_at=NOW() WHERE id=(SELECT schedule_id FROM activity_results WHERE id=:id)")
                .setParameter("id", hidden).executeUpdate());
        assertThat(activityService.listPublicActivities(ActivityScopeType.TEAM, teamId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(activityService.findPublicActivitiesForSitemap(List.of(teamId), List.of()))
                .extracting(row -> row.activityId()).containsExactly(manual);
    }

    @Test
    void 既に完了した予定を未来へ延期するとHTTP応答も活動も予定へ戻る() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("UPDATE schedules SET status='COMPLETED' WHERE id=:id")
                    .setParameter("id", scheduleId).executeUpdate();
            em.createNativeQuery("UPDATE activity_results SET is_planned=FALSE WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
        });
        var response = mvc.perform(patch("/api/v1/teams/{team}/schedules/{id}", teamId, scheduleId)
                        .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("endAt", "2026-12-16T01:00:34+09:00"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("data").path("content")
                .path("status").asText()).isEqualTo("SCHEDULED");
        String end = tx.execute(t -> schedules.findById(scheduleId).orElseThrow().getEndAt().toString());
        assertThat(end).isEqualTo("2026-12-16T01:00:34");
        assertThat(detail(id, AUTHOR).path("isPlanned").asBoolean()).isTrue();
    }

    @Test
    void 退会したauto作者は削除できず元予定と実績を保持する() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM memberships WHERE user_id=:user AND scope_type='TEAM' AND scope_id=:id")
                    .setParameter("user", AUTHOR).setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id=:user AND team_id=:id")
                    .setParameter("user", AUTHOR).setParameter("id", teamId).executeUpdate();
        });
        mvc.perform(delete("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR))))
                .andExpect(status().isForbidden());
        assertThat(activityId(scheduleId)).isEqualTo(id);
    }

    @Test
    void メタデータ読者は未公開コメントの別APIを閲覧作成できない() throws Exception {
        long id = activityId(createSchedule(Map.of()));
        mvc.perform(post("/api/v1/activities/{id}/comments", id).with(user(Long.toString(AUTHOR)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"未公開実績コメント\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/activities/{id}/comments", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/comments", id).with(user(Long.toString(MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"metadataからの不正追記\"}"))
                .andExpect(status().isNotFound());
        // 参加者の独立GETは存在しない。閲覧は詳細DTOのparticipants redactionで別試験する。
    }

    @Test
    void 一般統計とCSVにauto未公開実績件数や手編集題名を混ぜない() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        Long templateId = tx.execute(t -> {
            var template = com.mannschaft.app.activity.entity.ActivityTemplateEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(teamId).name("統計対照テンプレート")
                    .createdBy(AUTHOR).build();
            em.persist(template);
            em.flush();
            em.createNativeQuery("UPDATE activity_results SET title='秘匿実績CSV題名',template_id=:template WHERE id=:id")
                    .setParameter("template", template.getId()).setParameter("id", id).executeUpdate();
            em.createNativeQuery("INSERT INTO activity_participants(activity_result_id,user_id,role_label,created_at) VALUES (:id,:user,'秘匿参加者',NOW())")
                    .setParameter("id", id).setParameter("user", AUTHOR).executeUpdate();
            return template.getId();
        });
        try {
            // 非空の未公開actualも、予定が見えるだけの一般所属者の統計へ混ぜない。
            var response = mvc.perform(get("/api/v1/activities/stats").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn();
            var stats = json.readTree(response.getResponse().getContentAsString()).path("data");
            assertThat(stats.path("totalActivities").asLong()).isZero();
            for (String key : List.of("byMonth", "byTemplate", "topParticipants")) {
                assertThat(stats.path(key).size()).as(key).isZero();
            }
            var csv = mvc.perform(get("/api/v1/activities/export").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(csv.replace("\uFEFF", "").lines().toList()).containsExactly("日付,タイトル,参加者数,作成者");
            tx.executeWithoutResult(t -> {
                em.createNativeQuery("UPDATE activity_results SET status='PUBLISHED',visibility='PUBLIC',is_planned=FALSE WHERE id=:id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("UPDATE schedules SET min_view_role='ADMIN_ONLY' WHERE id=:id")
                        .setParameter("id", scheduleId).executeUpdate();
            });
            assertThat(visibilityChecker.canView(com.mannschaft.app.common.visibility.ReferenceType.SCHEDULE, scheduleId, MEMBER)).isFalse();
            // 公開autoでも恒久source ACLを統計・参加者集計・CSVへ適用する。
            var hiddenResponse = mvc.perform(get("/api/v1/activities/stats").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn();
            var hiddenStats = json.readTree(hiddenResponse.getResponse().getContentAsString()).path("data");
            assertThat(hiddenStats.path("totalActivities").asLong()).isZero();
            for (String key : List.of("byMonth", "byTemplate", "topParticipants")) {
                assertThat(hiddenStats.path(key).size()).as(key).isZero();
            }
            var hiddenCsv = mvc.perform(get("/api/v1/activities/export").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(hiddenCsv.replace("\uFEFF", "").lines().toList()).containsExactly("日付,タイトル,参加者数,作成者");
            Long manualId = tx.execute(t -> {
                var manual = activities.saveAndFlush(com.mannschaft.app.activity.entity.ActivityResultEntity.builder()
                        .scopeType(ActivityScopeType.TEAM).scopeId(teamId).templateId(templateId)
                        .title("閲覧可能な手動実績CSV題名").activityDate(java.time.LocalDate.of(2026, 10, 15))
                        .createdBy(AUTHOR).status(ActivityStatus.PUBLISHED).visibility(ActivityVisibility.MEMBERS_ONLY).build());
                em.createNativeQuery("INSERT INTO activity_participants(activity_result_id,user_id,role_label,created_at) VALUES (:id,:user,'手動参加者',NOW())")
                        .setParameter("id", manual.getId()).setParameter("user", MEMBER).executeUpdate();
                return manual.getId();
            });
            assertThat(detail(manualId, MEMBER).path("title").asText()).isEqualTo("閲覧可能な手動実績CSV題名");
            var visibleResponse = mvc.perform(get("/api/v1/activities/stats").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn();
            var visibleStats = json.readTree(visibleResponse.getResponse().getContentAsString()).path("data");
            assertThat(visibleStats.path("totalActivities").asLong()).isEqualTo(1);
            assertThat(visibleStats.path("byTemplate").size()).isEqualTo(1);
            assertThat(visibleStats.path("byTemplate").get(0).path("templateId").asLong()).isEqualTo(templateId);
            assertThat(visibleStats.path("byTemplate").get(0).path("count").asLong()).isEqualTo(1);
            assertThat(visibleStats.path("byMonth").get(0).path("count").asLong()).isEqualTo(1);
            assertThat(visibleStats.path("topParticipants").size()).isEqualTo(1);
            assertThat(visibleStats.path("topParticipants").get(0).path("userId").asLong()).isEqualTo(MEMBER);
            assertThat(visibleStats.path("topParticipants").get(0).path("participationCount").asLong()).isEqualTo(1);
            var visibleCsv = mvc.perform(get("/api/v1/activities/export").with(user(Long.toString(MEMBER)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(visibleCsv.replace("\uFEFF", "").lines().toList()).containsExactly("日付,タイトル,参加者数,作成者",
                    "2026-10-15,閲覧可能な手動実績CSV題名,1," + AUTHOR);
        } finally {
            tx.executeWithoutResult(t -> {
                em.createNativeQuery("UPDATE activity_results SET template_id=NULL WHERE scope_type='TEAM' AND scope_id=:scope")
                        .setParameter("scope", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE id=:id AND scope_type='TEAM' AND scope_id=:scope")
                        .setParameter("id", templateId).setParameter("scope", teamId).executeUpdate();
            });
        }
    }

    @Test
    void 予定POSTのcommit後に自動下書きが一件だけ存在する() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long activityId = activityId(scheduleId);
        JsonNode detail = detail(activityId, AUTHOR);
        for (String key : List.of("autoGeneratedFromSchedule", "isPlanned", "metadataOnly", "canDelete")) {
            assertThat(detail.has(key)).as(key + "が実JSONに存在する").isTrue();
            assertThat(detail.path(key).isBoolean()).as(key + "はboolean").isTrue();
        }
        assertThat(detail.path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.path("autoGeneratedFromSchedule").asBoolean()).isTrue();
        assertThat(detail.path("isPlanned").asBoolean()).isTrue();
        assertThat(detail.path("metadataOnly").asBoolean()).isFalse();
        assertThat(detail.path("templateId").isNull()).isTrue();
        assertThat(detail.path("participants").size()).isZero();
        assertThat(detail.path("activityEndDate").asText()).isEqualTo("2026-10-16");
        assertThat(detail.path("activityTimeStart").asText()).isEqualTo("23:00:12");
        assertThat(detail.path("activityTimeEnd").asText()).isEqualTo("01:00:34");
    }

    @Test
    void 一般所属の一覧と詳細は予定メタデータだけ返す() throws Exception {
        long id = activityId(createSchedule(Map.of()));
        tx.executeWithoutResult(transaction -> {
            em.createNativeQuery("UPDATE activity_results SET title='非公開手編集題名', description='未公開本文', "
                    + "field_values='{\"result\":123}', attachments='{\"file_ids\":[987654]}', location='実績会場' WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            em.createNativeQuery("INSERT INTO activity_participants(activity_result_id,user_id,role_label,created_at) "
                    + "VALUES (:id,:user,'非公開実参加者',NOW())")
                    .setParameter("id", id).setParameter("user", AUTHOR).executeUpdate();
        });
        assertThat(detail(id, AUTHOR).path("description").asText()).isEqualTo("未公開本文");
        JsonNode detail = detail(id, MEMBER);
        assertThat(detail.path("metadataOnly").asBoolean()).isTrue();
        assertThat(detail.path("canEdit").asBoolean()).isFalse();
        assertThat(detail.path("canPublish").asBoolean()).isFalse();
        assertThat(detail.path("canDelete").asBoolean()).isFalse();
        assertThat(detail.path("title").asText()).isEqualTo("自動予定");
        for (String field : List.of("description", "fieldValues", "attachments", "templateId", "createdBy",
                "location", "venueId", "createdAt", "updatedAt", "version")) {
            assertThat(detail.path(field).isNull()).as(field).isTrue();
        }
        assertThat(detail.path("participants").size()).isZero();
        assertThat(detail.path("templateFields").size()).isZero();
        var response = mvc.perform(get("/api/v1/activities").with(user(Long.toString(MEMBER)))
                        .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = json.readTree(response.getResponse().getContentAsString());
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("meta").path("total").asLong()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("metadataOnly").asBoolean()).isTrue();
    }

    @Test
    void 元予定の閲覧ロールに不適合なら自動活動のIDと件数を返さない() throws Exception {
        long id = activityId(createSchedule(Map.of("minViewRole", "ADMIN_ONLY")));
        mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(MEMBER))))
                .andExpect(status().isNotFound());
        var response = mvc.perform(get("/api/v1/activities").with(user(Long.toString(MEMBER)))
                        .param("scope_type", "TEAM").param("scope_id", teamId.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = json.readTree(response.getResponse().getContentAsString());
        assertThat(list.path("data").size()).isZero();
        assertThat(list.path("meta").path("total").asLong()).isZero();
    }

    @Test
    void 同じ内容の別POSTは別予定と各一件の記録になる() throws Exception {
        long first = createSchedule(Map.of());
        long second = createSchedule(Map.of());
        assertThat(second).isNotEqualTo(first);
        assertThat(activityId(second)).isNotEqualTo(activityId(first));
    }

    @Test
    void 古いRRスナップショットでも別TXの削除済みauto履歴から再生成しない() throws Exception {
        long scheduleId = createSchedule(Map.of());
        tx.executeWithoutResult(t -> em.createNativeQuery("DELETE FROM activity_results WHERE schedule_id=:id")
                .setParameter("id", scheduleId).executeUpdate());
        var separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(t -> {
            // locking以前にconsistent snapshotを確立し、別接続の履歴挿入を不可視にする。
            assertThat(activities.findAllByScheduleIdOrderByIdAsc(scheduleId)).isEmpty();
            separate.executeWithoutResult(other -> {
                var source = sources.currentSources(List.of(scheduleId), true).getFirst();
                generation.createAutomatic(source, AUTHOR);
                em.createNativeQuery("UPDATE activity_results SET deleted_at=NOW() WHERE schedule_id=:id")
                        .setParameter("id", scheduleId).executeUpdate();
            });
            generation.createAutomatic(sources.currentSources(List.of(scheduleId), true).getFirst(), AUTHOR);
        });
        Number historyCount = tx.execute(t -> (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM activity_results WHERE schedule_id=:id AND is_auto_generated_from_schedule=TRUE")
                .setParameter("id", scheduleId).getSingleResult());
        assertThat(historyCount.longValue()).isEqualTo(1);
        Integer activeCount = tx.execute(t -> activities.findAllByScheduleIdOrderByIdAsc(scheduleId).size());
        assertThat(activeCount).isEqualTo(0);
    }

    @Test
    void 自動活動を削除しても元予定は保持され更新で復活しない() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        assertThat(detail(id, AUTHOR).path("canDelete").asBoolean()).isTrue();
        mvc.perform(delete("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{id}", teamId, scheduleId)
                        .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "予定だけ更新"))))
                .andExpect(status().isOk());
        String savedTitle = tx.execute(transaction -> schedules.findById(scheduleId).orElseThrow().getTitle());
        assertThat(savedTitle).isEqualTo("予定だけ更新");
        Integer remaining = tx.execute(transaction -> activities.findAllByScheduleIdOrderByIdAsc(scheduleId).size());
        assertThat(remaining).isZero();
    }

    @Test
    void 自動活動削除後の明示作成は手動下書きとして保存する() throws Exception {
        long scheduleId = createSchedule(Map.of());
        long id = activityId(scheduleId);
        mvc.perform(delete("/api/v1/activities/{id}", id).with(user(Long.toString(AUTHOR))))
                .andExpect(status().isNoContent());
        var response = mvc.perform(post("/api/v1/activities/draft-from-schedule")
                        .with(user(Long.toString(AUTHOR))).param("scope_type", "TEAM")
                        .param("scope_id", teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scheduleId", scheduleId))))
                .andExpect(status().isOk()).andReturn();
        JsonNode manual = json.readTree(response.getResponse().getContentAsString()).path("data");
        assertThat(manual.path("id").asLong()).isNotEqualTo(id);
        assertThat(manual.path("autoGeneratedFromSchedule").asBoolean()).isFalse();
        assertThat(manual.path("isPlanned").asBoolean()).isFalse();
    }

    private long createSchedule(Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "自動予定", "startAt", "2026-10-15T23:00:12+09:00",
                "endAt", "2026-10-16T01:00:34+09:00", "allDay", false, "eventType", "PRACTICE",
                "attendanceRequired", false, "visibility", "MEMBERS_ONLY", "minViewRole", "MEMBER_PLUS"));
        body.putAll(overrides);
        var response = mvc.perform(post("/api/v1/teams/{team}/schedules", teamId).with(user(Long.toString(AUTHOR)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        return id;
    }

    private long activityId(long scheduleId) {
        List<Long> ids = tx.execute(transaction -> activities.findAllByScheduleIdOrderByIdAsc(scheduleId).stream()
                .map(activity -> activity.getId()).toList());
        assertThat(ids).as("予定保存TXが自動活動を一件commitする").hasSize(1);
        return ids.getFirst();
    }

    private JsonNode detail(long id, long viewer) throws Exception {
        var response = mvc.perform(get("/api/v1/activities/{id}", id).with(user(Long.toString(viewer))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
}
