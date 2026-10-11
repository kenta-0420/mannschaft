package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.schedule.DateShiftMode;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService;
import com.mannschaft.app.schedule.service.ScheduleKeepService;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.adapter.ScheduleAnnouncementAdapter;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通常POST以外の具体INSERT入口を、実保存commit後の別TXで確認する。 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityAutomaticCreationPathsIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long AUTHOR = 940208401L;
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-15T23:00:12+09:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-16T01:00:34+09:00");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ActivityResultRepository activities;
    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleAnnualCopyService annual;
    @Autowired private ScheduleKeepService keeps;
    @Autowired private ScheduleAnnouncementAdapter announcements;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate tx;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> {
            MembershipTestHelper.insertActiveUser(em, AUTHOR);
            String suffix = UUID.randomUUID().toString().substring(0, 12);
            teamId = teams.saveAndFlush(TeamEntity.builder().name("生成入口試練")
                    .slug("paths-team-" + suffix).visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true).build()).getId();
            orgId = organizations.saveAndFlush(OrganizationEntity.builder().name("生成入口組織試練")
                    .slug("paths-org-" + suffix).orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(true).build()).getId();
            MembershipTestHelper.insertMembership(em, AUTHOR, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, AUTHOR, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, AUTHOR, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, AUTHOR, "ADMIN", null, orgId);
        });
    }

    @AfterEach
    void cleanup() {
        if (teamId == null || orgId == null) return;
        tx.executeWithoutResult(t -> {
            for (Scope scope : scopes()) {
                em.createNativeQuery("DELETE FROM activity_results WHERE scope_type=:type AND scope_id=:id")
                        .setParameter("type", scope.type()).setParameter("id", scope.id()).executeUpdate();
                String scopeColumn = scope.team() ? "team_id" : "organization_id";
                for (String table : List.of("schedule_keeps", "schedule_annual_copy_logs")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE " + scopeColumn + "=:id")
                            .setParameter("id", scope.id()).executeUpdate();
                }
                // 繰返し親子のFK順序も守り、当該所有scope以外を消さない。
                em.createNativeQuery("DELETE FROM schedules WHERE " + scopeColumn + "=:id AND parent_schedule_id IS NOT NULL")
                        .setParameter("id", scope.id()).executeUpdate();
                em.createNativeQuery("DELETE FROM schedules WHERE " + scopeColumn + "=:id")
                        .setParameter("id", scope.id()).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type=:type AND scope_id=:id")
                        .setParameter("type", scope.type()).setParameter("id", scope.id()).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE " + scopeColumn + "=:id")
                        .setParameter("id", scope.id()).executeUpdate();
            }
            organizations.deleteById(orgId);
            teams.deleteById(teamId);
        });
    }

    @Test
    void TEAMとORGの繰返し親は作らず各具体子に一件だけ生成する() throws Exception {
        for (Scope scope : scopes()) {
            long parent = create(scope, Map.of("recurrenceRule", recurrence()));
            List<Long> children = tx.execute(t -> schedules.findByParentScheduleIdOrderByStartAtAsc(parent)
                    .stream().map(s -> s.getId()).toList());
            assertThat(children).hasSize(3);
            assertThat(linkCount(parent)).isZero();
            for (Long id : children) assertAutomatic(id, scope);
            assertThat(scopeActivityCount(scope)).isEqualTo(3);
        }
    }

    @Test
    void 複製と別scope受諾用複製は実績をコピーせず各新予定に一件生成する() throws Exception {
        Scope team = scopes().getFirst();
        Scope org = scopes().getLast();
        long original = create(team, Map.of());
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE activity_results SET description='元の実績本文' WHERE schedule_id=:id")
                .setParameter("id", original).executeUpdate());
        long duplicate = scheduleService.duplicateSchedule(original, AUTHOR).getId();
        long cross = scheduleService.duplicateScheduleIntoScope(original, "ORGANIZATION", orgId, AUTHOR).getId();
        assertThat(List.of(original, duplicate, cross)).doesNotHaveDuplicates();
        assertAutomatic(duplicate, team);
        assertAutomatic(cross, org);
        assertThat(linkCount(original)).isEqualTo(1);
        String originalBody = tx.execute(t -> activities.findAllByScheduleIdOrderByIdAsc(original).getFirst().getDescription());
        assertThat(originalBody).isEqualTo("元の実績本文");
    }

    @Test
    void 年間コピーは新年度の日時と作者で各scopeに一件生成する() throws Exception {
        for (Scope scope : scopes()) {
            long original = create(scope, Map.of("academicYear", 2026));
            LocalDateTime targetStart = START.toLocalDateTime().plusYears(1);
            LocalDateTime targetEnd = END.toLocalDateTime().plusYears(1);
            var result = annual.executeCopy(scope.id(), scope.team(), 2026, 2027, DateShiftMode.EXACT_DAYS,
                    List.of(new ScheduleAnnualCopyService.CopyItem(original, targetStart, targetEnd, true)), AUTHOR);
            assertThat(result.totalCopied()).isEqualTo(1);
            assertThat(result.createdScheduleIds()).hasSize(1);
            long id = result.createdScheduleIds().getFirst();
            assertAutomatic(id, scope);
            tx.executeWithoutResult(t -> {
                var activity = activities.findAllByScheduleIdOrderByIdAsc(id).getFirst();
                assertThat(activity.getActivityDate()).isEqualTo(targetStart.toLocalDate());
                assertThat(activity.getActivityEndDate()).isEqualTo(targetEnd.toLocalDate());
            });
            assertThat(linkCount(original)).isEqualTo(1);
        }
    }

    @Test
    void 日程未定keep自体には作らずTEAMとORGの変換先に一件生成する() {
        for (Scope scope : scopes()) {
            ScheduleKeepScope keepScope = scope.team() ? ScheduleKeepScope.team(scope.id()) : ScheduleKeepScope.organization(scope.id());
            var kept = keeps.create(keepScope, new CreateScheduleKeepRequest("keep具体化", null, null), AUTHOR);
            assertThat(scopeActivityCount(scope)).isZero();
            var converted = keeps.convert(keepScope, UUID.fromString(kept.getId()),
                    new ConvertScheduleKeepRequest(START.toLocalDateTime(), END.toLocalDateTime(), false), AUTHOR);
            assertThat(converted.getKeep().getStatus()).isEqualTo("SCHEDULED");
            assertAutomatic(converted.getSchedule().getId(), scope);
            assertThat(scopeActivityCount(scope)).isEqualTo(1);
        }
    }

    @Test
    void 告知adapterのTEAMとORG予定にも未公開実績本文をコピーせず一件生成する() {
        for (Scope scope : scopes()) {
            var content = AnnouncementContentRequest.builder().title("告知予定")
                    .description("予定の説明であり実績本文ではない").startAt(START).endAt(END).allDay(false).build();
            long id = announcements.createContent(content, scope.type(), scope.id(), "MEMBERS_AND_ABOVE", AUTHOR);
            assertAutomatic(id, scope);
            assertThat(scopeActivityCount(scope)).isEqualTo(1);
        }
    }

    @Test
    void 自動活動INSERT失敗は繰返し親子も同じ作成TXで全てrollbackする() throws Exception {
        String trigger = "auto_paths_fail_" + UUID.randomUUID().toString().replace("-", "");
        // 実MySQLの失敗を、この試験の所有scope/auto INSERTだけへ限定する。
        try {
            executeOwnedTriggerDdl("CREATE TRIGGER " + trigger + " BEFORE INSERT ON activity_results FOR EACH ROW BEGIN "
                    + "IF NEW.scope_type='TEAM' AND NEW.scope_id=" + teamId + " AND NEW.is_auto_generated_from_schedule=TRUE THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned auto creation trial failure'; END IF; END");
            CreateScheduleRequest request = json.convertValue(body(Map.of("recurrenceRule", recurrence())), CreateScheduleRequest.class);
            assertThatThrownBy(() -> scheduleService.createSchedule(request, teamId, "TEAM", AUTHOR))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("owned auto creation trial failure");
            Number sourceCount = tx.execute(t -> (Number) em.createNativeQuery("SELECT COUNT(*) FROM schedules WHERE team_id=:id")
                    .setParameter("id", teamId).getSingleResult());
            assertThat(sourceCount.longValue()).isZero();
            assertThat(scopeActivityCount(scopes().getFirst())).isZero();
        } finally {
            executeOwnedTriggerDdl("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    /** app試験userの権限は変えず、同じ所有Testcontainerの管理接続で試験triggerのDDLだけ実行する。 */
    private void executeOwnedTriggerDdl(String sql) throws SQLException {
        if (!MYSQL.isRunning() || !"mannschaft_test".equals(MYSQL.getDatabaseName())) {
            throw new IllegalStateException("所有MySQL Testcontainerではない");
        }
        String rootPassword = MYSQL.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        if (rootPassword == null || rootPassword.isBlank()) {
            throw new IllegalStateException("Testcontainer管理接続設定がない");
        }
        // protected MYSQL自身のendpointを使い、Spring DataSourceや共有3306へfallbackしない。
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long create(Scope scope, Map<String, Object> overrides) throws Exception {
        var response = mvc.perform(post("/api/v1/" + (scope.team() ? "teams" : "organizations") + "/{id}/schedules", scope.id())
                        .with(user(Long.toString(AUTHOR))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(overrides))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Map<String, Object> body(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>(Map.of("title", "生成入口予定", "startAt", START.toString(),
                "endAt", END.toString(), "allDay", false, "eventType", "PRACTICE", "attendanceRequired", false,
                "visibility", "MEMBERS_ONLY", "minViewRole", "MEMBER_PLUS"));
        body.putAll(overrides);
        return body;
    }

    private Map<String, Object> recurrence() {
        return Map.of("type", "DAILY", "interval", 1, "endType", "COUNT", "count", 3);
    }

    private void assertAutomatic(long scheduleId, Scope scope) {
        tx.executeWithoutResult(t -> {
            var links = activities.findAllByScheduleIdOrderByIdAsc(scheduleId);
            assertThat(links).as("新予定ごとに一件commitする").hasSize(1);
            var activity = links.getFirst();
            assertThat(activity.getScopeType().name()).isEqualTo(scope.type());
            assertThat(activity.getScopeId()).isEqualTo(scope.id());
            assertThat(activity.getCreatedBy()).isEqualTo(AUTHOR);
            assertThat(activity.isAutoGeneratedFromSchedule()).isTrue();
            assertThat(activity.isPlanned()).isTrue();
            assertThat(activity.getStatus()).isEqualTo(ActivityStatus.DRAFT);
            assertThat(activity.getDescription()).isNull();
            assertThat(activity.getTemplateId()).isNull();
        });
    }

    private int linkCount(long scheduleId) {
        return tx.execute(t -> activities.findAllByScheduleIdOrderByIdAsc(scheduleId).size());
    }

    private long scopeActivityCount(Scope scope) {
        Number count = tx.execute(t -> (Number) em.createNativeQuery("SELECT COUNT(*) FROM activity_results WHERE scope_type=:type AND scope_id=:id")
                .setParameter("type", scope.type()).setParameter("id", scope.id()).getSingleResult());
        return count.longValue();
    }

    private List<Scope> scopes() { return List.of(new Scope("TEAM", teamId), new Scope("ORGANIZATION", orgId)); }
    private record Scope(String type, Long id) { boolean team() { return "TEAM".equals(type); } }
}
