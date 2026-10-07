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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CMP-261007-1510。実認可・実MySQLによって生成と確認同期の業務契約を固定する。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@WithMockUser(username = "940200001")
class ActivityScheduleSyncIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long AUTHOR = 940200001L;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ActivityResultRepository activities;
    @Autowired private com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository attendance;
    @Autowired private com.mannschaft.app.activity.repository.ActivityTemplateRepository templates;
    @Autowired private com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository templateFields;
    @PersistenceContext private EntityManager em;
    private Long teamId;
    private Long scheduleId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("INSERT INTO teams(name,visibility,supporter_enabled,version,member_count,slug,created_at,updated_at) VALUES('同期試練','PUBLIC',1,0,0,CONCAT('sync-',LEFT(REPLACE(UUID(),'-',''),8)),NOW(),NOW())").executeUpdate();
        teamId = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        MembershipTestHelper.insertActiveUser(em, AUTHOR);
        MembershipTestHelper.insertActiveUser(em, 940200002L);
        MembershipTestHelper.insertMembership(em, AUTHOR, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, AUTHOR, "ADMIN", teamId, null);
        scheduleId = schedules.saveAndFlush(ScheduleEntity.builder().teamId(teamId).title("予定由来")
                .startAt(LocalDateTime.of(2026, 10, 10, 23, 0)).endAt(LocalDateTime.of(2026, 10, 11, 1, 0))
                .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED).createdBy(AUTHOR).build()).getId();
    }

    @Test
    void 作成連打は同じ記録を返し跨日も永続する() throws Exception {
        JsonNode first = create();
        assertThat(create().path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(first.path("status").asText()).isEqualTo("DRAFT");
        assertThat(first.path("activityEndDate").asText()).isEqualTo("2026-10-11");
        em.flush(); em.clear();
        assertThat(activities.findById(first.path("id").asLong()).orElseThrow().getScheduleId()).isEqualTo(scheduleId);
    }

    @Test
    void 未編集下書きは基本項目だけ自動同期する() throws Exception {
        long id = create().path("id").asLong();
        save(update("更新予定"), null, 200);
        em.flush(); em.clear();
        assertThat(activities.findById(id).orElseThrow().getTitle()).isEqualTo("更新予定");
        assertThat(activities.findById(id).orElseThrow().getDescription()).isNull();
    }

    @Test
    void 明示した空選択は自動項目も同期しない() throws Exception {
        JsonNode activity = create();
        JsonNode preview = preview(update("予定だけ更新"));
        var confirmation = Map.of("expectedScheduleState", preview.get("expectedScheduleState"),
                "activities", java.util.List.of(Map.of("id", activity.path("id").asLong(),
                        "version", activity.path("version").asLong(), "applyFields", java.util.List.of())));
        save(update("予定だけ更新"), confirmation, 200);
        em.flush(); em.clear();
        assertThat(activities.findById(activity.path("id").asLong()).orElseThrow().getTitle()).isEqualTo("予定由来");
    }

    @Test
    void 手動編集後は確認必須で保護値を保存する() throws Exception {
        JsonNode activity = create();
        edit(activity, "手動タイトル");
        JsonNode preview = preview(update("新予定"));
        assertThat(preview.path("activities").get(0).path("changes").toString()).contains("\"automatic\":false");
        save(update("新予定"), null, 409);
        em.clear();
        assertThat(activities.findById(activity.path("id").asLong()).orElseThrow().getDescription()).isEqualTo("保護する結果本文");
        assertThat(schedules.findById(scheduleId).orElseThrow().getTitle()).isEqualTo("予定由来");
    }

    @Test
    void プレビュー後活動編集は競合し予定更新も戻る() throws Exception {
        JsonNode activity = create();
        JsonNode preview = preview(update("古い確認"));
        edit(activity, "編集後");
        var confirmation = Map.of("expectedScheduleState", preview.get("expectedScheduleState"),
                "activities", java.util.List.of(Map.of("id", activity.path("id").asLong(),
                        "version", activity.path("version").asLong(), "applyFields", java.util.List.of("title"))));
        save(update("古い確認"), confirmation, 409);
        em.clear();
        assertThat(schedules.findById(scheduleId).orElseThrow().getTitle()).isEqualTo("予定由来");
    }

    @Test
    @WithMockUser(username = "940200002")
    void 非所属ユーザーは予定から活動作成できない() throws Exception {
        mvc.perform(post("/api/v1/activities/draft-from-schedule").param("scope_type", "TEAM")
                .param("scope_id", teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleId", scheduleId)))).andExpect(status().isForbidden());
    }

    @Test
    void 通常作成は関連済み予定を409にし既存内容を変えない() throws Exception {
        JsonNode first = create();
        mvc.perform(post("/api/v1/activities").param("scope_type", "TEAM").param("scope_id", teamId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("templateId", 1,
                        "title", "保存されない入力", "activityDate", "2026-10-10", "scheduleId", scheduleId))))
                .andExpect(status().isConflict());
        assertThat(activities.findById(first.path("id").asLong()).orElseThrow().getTitle()).isEqualTo("予定由来");
    }

    @Test
    void 元予定と別scopeを指定すると404で関連を作らない() throws Exception {
        mvc.perform(post("/api/v1/activities/draft-from-schedule").param("scope_type", "TEAM")
                .param("scope_id", Long.toString(teamId + 100000)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleId", scheduleId)))).andExpect(status().isNotFound());
        assertThat(activities.findAllByScheduleIdOrderByIdAsc(scheduleId)).isEmpty();
    }

    @Test
    void 他作者下書きは同scope管理者に再利用される() throws Exception {
        var existing = activities.saveAndFlush(com.mannschaft.app.activity.entity.ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.TEAM).scopeId(teamId).scheduleId(scheduleId).createdBy(940200002L)
                .status(ActivityStatus.DRAFT).title("他作者の下書き").activityDate(java.time.LocalDate.of(2026, 10, 10)).build());
        assertThat(create().path("id").asLong()).isEqualTo(existing.getId());
    }

    @Test
    void 未編集と手動判定は値を戻しても解除しない() throws Exception {
        JsonNode activity = create();
        edit(activity, "手動タイトル");
        JsonNode changed = json.readTree(mvc.perform(get("/api/v1/activities/{id}", activity.path("id").asLong()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        edit(changed, "予定由来");
        JsonNode preview = preview(update("新しい予定"));
        assertThat(preview.path("activities").get(0).path("changes").get(0).path("automatic").asBoolean()).isFalse();
    }

    @Test
    void 添付と0nullの旧結果はタイトル編集で保持する() throws Exception {
        JsonNode activity = create();
        var entity = activities.findById(activity.path("id").asLong()).orElseThrow();
        entity.update(entity.getTitle(), entity.getActivityDate(), entity.getActivityTimeStart(), entity.getActivityTimeEnd(),
                "旧本文", "{\"zero\":0,\"empty\":null}", "{\"file_ids\":[7]}", entity.getVisibility());
        activities.flush();
        mvc.perform(put("/api/v1/activities/{id}", entity.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "新題名", "activityDate", "2026-10-10",
                        "activityEndDate", "2026-10-11", "activityTimeStart", "23:00:00", "activityTimeEnd", "01:00:00"))))
                .andExpect(status().isOk());
        em.flush(); em.clear();
        var saved = activities.findById(entity.getId()).orElseThrow();
        assertThat(json.readTree(saved.getAttachments()).path("file_ids").get(0).asLong()).isEqualTo(7L);
        assertThat(json.readTree(saved.getFieldValues()).path("zero").asInt()).isZero();
        assertThat(json.readTree(saved.getFieldValues()).path("empty").isNull()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"THIS_ONLY", "THIS_AND_FOLLOWING", "ALL"})
    void 繰返し更新scopeごとの実対象だけを同期する(String scope) throws Exception {
        recurringParent();
        Long child = child(12);
        Long following = child(14);
        JsonNode parentActivity = create();
        JsonNode childActivity = draftFor(child);
        JsonNode followingActivity = draftFor(following);
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, child).param("updateScope", scope)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "範囲更新"))))
                .andExpect(status().isOk());
        em.flush(); em.clear();
        assertThat(activities.findById(parentActivity.path("id").asLong()).orElseThrow().getTitle())
                .isEqualTo("ALL".equals(scope) ? "範囲更新" : "予定由来");
        assertThat(activities.findById(childActivity.path("id").asLong()).orElseThrow().getTitle()).isEqualTo("範囲更新");
        assertThat(activities.findById(followingActivity.path("id").asLong()).orElseThrow().getTitle())
                .isEqualTo("THIS_ONLY".equals(scope) ? "子予定14" : "範囲更新");
    }

    @Test
    void プレビュー後の非選択兄弟変更は409にする() throws Exception {
        recurringParent();
        Long sibling = child(12);
        create();
        var update = Map.<String, Object>of("title", "ALL更新");
        JsonNode preview = previewScope(update, "ALL");
        schedules.saveAndFlush(schedules.findById(sibling).orElseThrow().toBuilder().title("別編集").build());
        saveScope(update, previewConfirmation(preview), "ALL", 409);
    }

    @Test
    void プレビュー後の予定集合追加は409にする() throws Exception {
        recurringParent(); child(12); create();
        var update = Map.<String, Object>of("title", "ALL更新");
        JsonNode preview = previewScope(update, "ALL");
        child(14);
        saveScope(update, previewConfirmation(preview), "ALL", 409);
    }

    @Test
    void プレビュー後の関連活動追加は409にする() throws Exception {
        create();
        var update = update("関連集合確認");
        JsonNode preview = preview(update);
        activities.saveAndFlush(com.mannschaft.app.activity.entity.ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.TEAM).scopeId(teamId).scheduleId(scheduleId).createdBy(AUTHOR)
                .status(ActivityStatus.DRAFT).title("追加された旧リンク").activityDate(java.time.LocalDate.of(2026, 10, 10)).build());
        save(update, previewConfirmation(preview), 409);
    }

    @Test
    void 出席者は最初だけ複写され後続の出欠同期はしない() throws Exception {
        attendance.saveAndFlush(com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity.builder()
                .scheduleId(scheduleId).userId(AUTHOR).status(com.mannschaft.app.schedule.AttendanceStatus.ATTENDING).build());
        attendance.saveAndFlush(com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity.builder()
                .scheduleId(scheduleId).userId(940200002L).status(com.mannschaft.app.schedule.AttendanceStatus.ABSENT).build());
        JsonNode activity = create();
        assertThat(activity.path("participants").size()).isEqualTo(1);
        assertThat(activity.path("participants").get(0).path("userId").asLong()).isEqualTo(AUTHOR);
        attendance.findByScheduleIdAndStatus(scheduleId, com.mannschaft.app.schedule.AttendanceStatus.ATTENDING).getFirst()
                .respond(com.mannschaft.app.schedule.AttendanceStatus.ABSENT, null);
        attendance.flush();
        save(update("出欠は同期しない"), null, 200);
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/activities/{id}", activity.path("id").asLong()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(detail.path("participants").size()).isEqualTo(1);
        assertThat(detail.path("participants").get(0).path("userId").asLong()).isEqualTo(AUTHOR);
    }

    private void recurringParent() {
        schedules.saveAndFlush(schedules.findById(scheduleId).orElseThrow().toBuilder().recurrenceRule("{\"frequency\":\"DAILY\"}").build());
    }

    @Test
    void 参加者変更は既存rowの役割ラベルを保持する() throws Exception {
        JsonNode activity = create();
        var added = mvc.perform(post("/api/v1/activities/{id}/participants", activity.path("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userIds", java.util.List.of(AUTHOR),
                        "roleLabels", Map.of(Long.toString(AUTHOR), "主担当")))))
                .andExpect(status().isOk()).andReturn();
        long existingId = json.readTree(added.getResponse().getContentAsString()).path("data").get(0).path("id").asLong();
        mvc.perform(put("/api/v1/activities/{id}", activity.path("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", "参加者編集", "activityDate", "2026-10-10", "activityEndDate", "2026-10-11",
                        "participantUserIds", java.util.List.of(AUTHOR, 940200002L)))))
                .andExpect(status().isOk());
        var result = mvc.perform(get("/api/v1/activities/{id}", activity.path("id").asLong())).andExpect(status().isOk()).andReturn();
        var participants = json.readTree(result.getResponse().getContentAsString()).path("data").path("participants");
        assertThat(participants.size()).isEqualTo(2);
        assertThat(participants.get(0).path("id").asLong()).isEqualTo(existingId);
        assertThat(participants.get(0).path("roleLabel").asText()).isEqualTo("主担当");
    }

    @Test
    void 非作者下書きは誤versionでも版競合より先に拒否する() throws Exception {
        JsonNode activity = create();
        MembershipTestHelper.insertMembership(em, 940200002L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        mvc.perform(put("/api/v1/activities/{id}", activity.path("id").asLong())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("940200002"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "拒否", "activityDate", "2026-10-10", "version", 999999))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("940200002"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 跨日記録複製は終了日も指定開始日に合わせて移動する() throws Exception {
        JsonNode activity = create();
        var result = mvc.perform(post("/api/v1/activities/{id}/duplicate", activity.path("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content("{\"activityDate\":\"2026-10-20\"}"))
                .andExpect(status().isCreated()).andReturn();
        Long copyId = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        var detailResult = mvc.perform(get("/api/v1/activities/{id}", copyId)).andExpect(status().isOk()).andReturn();
        var detail = json.readTree(detailResult.getResponse().getContentAsString()).path("data");
        assertThat(detail.path("activityEndDate").asText()).isEqualTo("2026-10-21");
        assertThat(detail.path("scheduleId").isNull()).isTrue();
    }

    @Test
    void 非対応scopeは予定の存在を調べる前に400にする() throws Exception {
        mvc.perform(post("/api/v1/activities/draft-from-schedule").param("scope_type", "PERSONAL")
                .param("scope_id", Long.toString(AUTHOR)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleId", scheduleId)))).andExpect(status().isBadRequest());
    }

    @Test
    void 公開もversion競合を拒否し旧本文なし公開は維持する() throws Exception {
        JsonNode activity = create();
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", activity.path("version").asLong() + 1))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", activity.path("version").asLong()))))
                .andExpect(status().isOk());
    }

    @Test
    void 全日予定の排他的終了を記録日付へ変換し時刻はnullにする() throws Exception {
        schedules.saveAndFlush(schedules.findById(scheduleId).orElseThrow().toBuilder().allDay(true)
                .startAt(LocalDateTime.of(2026, 10, 10, 0, 0)).endAt(LocalDateTime.of(2026, 10, 12, 0, 0)).build());
        JsonNode activity = create();
        assertThat(activity.path("activityEndDate").asText()).isEqualTo("2026-10-11");
        assertThat(activity.path("activityTimeStart").isNull()).isTrue();
        assertThat(activity.path("activityTimeEnd").isNull()).isTrue();
    }

    @Test
    void 削除された元予定は参照を秘匿し記録を保持する() throws Exception {
        JsonNode activity = create();
        schedules.findById(scheduleId).orElseThrow().softDelete();
        schedules.flush(); em.clear();
        var result = mvc.perform(get("/api/v1/activities/{id}", activity.path("id").asLong()))
                .andExpect(status().isOk()).andReturn();
        var detail = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(detail.path("scheduleId").asLong()).isEqualTo(scheduleId);
        assertThat(detail.path("sourceSchedule").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(detail.path("sourceSchedule").path("id").isNull()).isTrue();
        assertThat(detail.path("sourceSchedule").path("canView").asBoolean()).isFalse();
    }

    @Test
    void 公開済み記録は自動更新せず選択基本項目だけ同期する() throws Exception {
        JsonNode activity = create();
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong()))
                .andExpect(status().isOk());
        var update = update("公開後の予定");
        JsonNode preview = preview(update);
        assertThat(preview.path("activities").get(0).path("changes").get(0).path("automatic").asBoolean()).isFalse();
        save(update, null, 409);
        save(update, previewConfirmation(preview), 200);
        em.flush(); em.clear();
        assertThat(activities.findById(activity.path("id").asLong()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
    }

    @Test
    void テンプレート後付けは一度だけで0は必須数値として公開できる() throws Exception {
        JsonNode activity = create();
        var template = templates.saveAndFlush(com.mannschaft.app.activity.entity.ActivityTemplateEntity.builder()
                .scopeType(ActivityScopeType.TEAM).scopeId(teamId).name("結果追記").createdBy(AUTHOR).build());
        templateFields.saveAndFlush(com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity.builder()
                .templateId(template.getId()).fieldKey("score").fieldLabel("得点").fieldType(FieldType.NUMBER)
                .unit("点").isRequired(true).build());
        var body = new java.util.HashMap<String, Object>();
        body.put("title", "結果追記"); body.put("activityDate", "2026-10-10"); body.put("templateId", template.getId());
        body.put("activityEndDate", "2026-10-11"); body.put("fieldValues", Map.of("score", 0));
        mvc.perform(put("/api/v1/activities/{id}", activity.path("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk());
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/activities/{id}", activity.path("id").asLong()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(detail.path("templateFields").get(0).path("unit").asText()).isEqualTo("点");
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/activities/{id}/participants", activity.path("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userIds", java.util.List.of(AUTHOR)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/activities/{id}/publish", activity.path("id").asLong()))
                .andExpect(status().isOk());
        body.put("templateId", template.getId() + 1000);
        mvc.perform(put("/api/v1/activities/{id}", activity.path("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
    }

    @Test
    void 予定編集権だけでは他作者活動の値も存在も同期応答に含めない() throws Exception {
        JsonNode activity = create();
        MembershipTestHelper.insertMembership(em, 940200002L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        for (String permission : java.util.List.of("MANAGE_SCHEDULES", "MANAGE_FILES", "MANAGE_POSTS")) {
            em.createNativeQuery("INSERT INTO permissions(name,display_name,scope,created_at,updated_at) SELECT :name,:name,'TEAM',NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM permissions WHERE name=:name)")
                    .setParameter("name", permission).executeUpdate();
            em.createNativeQuery("INSERT INTO role_permissions(role_id,permission_id,is_default,created_at) SELECT r.id,p.id,0,NOW() FROM roles r CROSS JOIN permissions p WHERE r.name='MEMBER' AND p.name=:name AND NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id)")
                    .setParameter("name", permission).executeUpdate();
        }
        mvc.perform(put("/api/v1/admin/member-permissions").param("scopeType", "TEAM").param("scopeId", teamId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":[{\"name\":\"MANAGE_SCHEDULES\",\"enabled\":true},{\"name\":\"MANAGE_FILES\",\"enabled\":false},{\"name\":\"MANAGE_POSTS\",\"enabled\":false}]}"))
                .andExpect(status().isOk());
        var result = mvc.perform(post("/api/v1/teams/{team}/schedules/{schedule}/activity-sync-preview", teamId, scheduleId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("940200002"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("scheduleUpdate", update("予定担当だけ")))))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("data").path("activities").size()).isZero();
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, scheduleId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("940200002"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update("予定担当だけ"))))
                .andExpect(status().isOk());
        assertThat(activities.findById(activity.path("id").asLong()).orElseThrow().getTitle()).isEqualTo("予定由来");
        var hidden = mvc.perform(post("/api/v1/activities/draft-from-schedule").param("scope_type", "TEAM")
                .param("scope_id", teamId.toString()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("940200002"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("scheduleId", scheduleId))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(hidden.getResponse().getContentAsString()).has("data")).isFalse();
    }

    private Long child(int day) {
        return schedules.saveAndFlush(schedules.findById(scheduleId).orElseThrow().toBuilder().id(null)
                .parentScheduleId(scheduleId).recurrenceRule(null).isException(false).title("子予定" + day)
                .startAt(LocalDateTime.of(2026, 10, day, 23, 0)).endAt(LocalDateTime.of(2026, 10, day + 1, 1, 0)).build()).getId();
    }

    private Object previewConfirmation(JsonNode preview) {
        var selections = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : preview.path("activities")) {
            var fields = new java.util.ArrayList<String>();
            for (var change : entry.path("changes")) fields.add(change.path("field").asText());
            selections.add(Map.of("id", entry.path("id").asLong(), "version", entry.path("version").asLong(), "applyFields", fields));
        }
        return Map.of("expectedScheduleState", preview.get("expectedScheduleState"), "activities", selections);
    }

    private JsonNode previewScope(Map<String, Object> update, String scope) throws Exception {
        var result = mvc.perform(post("/api/v1/teams/{team}/schedules/{schedule}/activity-sync-preview", teamId, scheduleId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("scheduleUpdate", update, "updateScope", scope))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void saveScope(Map<String, Object> update, Object confirmation, String scope, int expected) throws Exception {
        var body = new java.util.HashMap<>(update); body.put("syncConfirmation", confirmation);
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, scheduleId).param("updateScope", scope)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected));
    }

    private JsonNode create() throws Exception {
        return draftFor(scheduleId);
    }

    private JsonNode draftFor(Long sourceId) throws Exception {
        var result = mvc.perform(post("/api/v1/activities/draft-from-schedule").param("scope_type", "TEAM")
                .param("scope_id", teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("scheduleId", sourceId)))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Map<String, Object> update(String title) {
        return Map.of("title", title, "startAt", "2026-10-10T23:00:00+09:00", "endAt", "2026-10-11T01:00:00+09:00", "allDay", false);
    }

    private JsonNode preview(Map<String, Object> update) throws Exception {
        var result = mvc.perform(post("/api/v1/teams/{team}/schedules/{schedule}/activity-sync-preview", teamId, scheduleId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("scheduleUpdate", update, "updateScope", "THIS_ONLY"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void save(Map<String, Object> update, Object confirmation, int expectedStatus) throws Exception {
        var body = new java.util.HashMap<>(update);
        if (confirmation != null) body.put("syncConfirmation", confirmation);
        mvc.perform(patch("/api/v1/teams/{team}/schedules/{schedule}", teamId, scheduleId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    private void edit(JsonNode activity, String title) throws Exception {
        mvc.perform(put("/api/v1/activities/{id}", activity.path("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title, "activityDate", "2026-10-10", "activityEndDate", "2026-10-11",
                        "activityTimeStart", "23:00:00", "activityTimeEnd", "01:00:00", "description", "保護する結果本文",
                        "fieldValues", Map.of("score", 0), "visibility", "MEMBERS_ONLY", "version", activity.path("version").asLong()))))
                .andExpect(status().isOk());
    }
}
