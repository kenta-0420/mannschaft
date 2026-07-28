package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * スケジュール一覧API（{@code GET /teams/{teamPublicId}/schedules} /
 * {@code GET /organizations/{orgPublicId}/schedules}）が閲覧者自身の出欠状態
 * （{@code myAttendanceStatus}）を実値でバッチ供給することを検証する契約テスト（試練 / red 先行）。
 *
 * <p><b>背景</b>: {@code ScheduleQueryService#toScheduleResponse} が一覧系のみ
 * {@code myAttendanceStatus(null)} 固定で返しており、モバイル行の初期出欠ハイライトが点灯しない
 * 欠陥があった（詳細GETは {@code ScheduleAttendanceService#getMyAttendanceStatus} 経由で実値を返す）。
 * 本テストは一覧系も詳細GETと同じ意味論（出欠レコードが存在しない = null、存在すれば実ステータス
 * 文字列）で実値を返すことを保証する。関連 #2453。</p>
 *
 * <p>金型: {@code ScheduleWriteScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。SQL 数の検証は
 * {@code MembershipBatchQueryServiceIntegrationTest} の Hibernate {@link Statistics} パターンを踏襲。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("スケジュール一覧 myAttendanceStatus バッチ供給 契約テスト（試練）")
class ScheduleListMyAttendanceStatusIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleAttendanceRepository attendanceRepository;

    @PersistenceContext
    private EntityManager em;

    private String teamSlug;
    private String orgSlug;
    private Long teamId;
    private Long orgId;

    private Long userAId; // 出欠回答本人
    private Long userBId; // 別ユーザー（漏洩検証用）

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 4, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 4, 30, 0, 0);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @BeforeEach
    void setUp() {
        teamSlug = "wb-myattend-team-" + System.nanoTime();
        orgSlug = "wb-myattend-org-" + System.nanoTime();

        teamId = insertTeam("MYATTEND チーム", teamSlug);
        orgId = insertOrganization("MYATTEND 組織", orgSlug);

        userAId = insertUser("myattend-a@example.com");
        userBId = insertUser("myattend-b@example.com");

        MembershipTestHelper.insertMembership(em, userAId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, userBId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, userAId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, userBId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1 / AC-3: チーム一覧
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1/AC-3: チーム一覧のmyAttendanceStatus")
    class TeamList {

        @Test
        @DisplayName("AC-1: ATTENDING回答済みの予定は一覧でもmyAttendanceStatus=ATTENDINGを返す")
        void 出欠必須予定にATTENDING回答済みなら一覧も実値() throws Exception {
            Long scheduleId = createTeamSchedule("出欠必須予定", true);
            generateAttendance(scheduleId, userAId);

            respondAttendance(userAId, scheduleId, "ATTENDING");

            Map<Long, String> statusById = getTeamScheduleStatuses(userAId);
            assertThat(statusById.get(scheduleId)).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("AC-3: 出欠不要（attendanceRequired=false）の予定はmyAttendanceStatus=null")
        void 出欠不要予定はnull() throws Exception {
            Long scheduleId = createTeamSchedule("出欠不要予定", false);
            // 出欠不要のため出欠レコードは一切生成しない（generateAttendanceRecordsを呼ばない）

            Map<Long, String> statusById = getTeamScheduleStatuses(userAId);
            assertThat(statusById.get(scheduleId)).isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2: 組織一覧
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2: 組織一覧のmyAttendanceStatus（team一覧と共用mapper）")
    class OrgList {

        @Test
        @DisplayName("AC-2: 組織スケジュール一覧でもPARTIAL回答済みなら実値を返す")
        void 組織一覧も実値を返す() throws Exception {
            Long scheduleId = createOrgSchedule("組織出欠必須予定", true);
            generateAttendance(scheduleId, userAId);

            respondAttendance(userAId, scheduleId, "PARTIAL");

            Map<Long, String> statusById = getOrgScheduleStatuses(userAId);
            assertThat(statusById.get(scheduleId)).isEqualTo("PARTIAL");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4: 認可（fail-closed・他者の回答が漏れない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-4: 認可 — 別ユーザーの出欠が漏れない")
    class Isolation {

        @Test
        @DisplayName("Bの回答はAの一覧に漏れない。Aは自分自身の実値（未回答ならUNDECIDED行）のみ見える")
        void 別ユーザーの回答は漏洩しない() throws Exception {
            Long scheduleId = createTeamSchedule("出欠必須予定(isolation)", true);
            // 両ユーザーに出欠レコードを生成（実運用の出欠募集を模した状態）。
            generateAttendance(scheduleId, userAId);
            generateAttendance(scheduleId, userBId);

            // Bのみ回答。Aは未回答のまま（レコードはUNDECIDEDで存在）。
            respondAttendance(userBId, scheduleId, "ATTENDING");

            Map<Long, String> statusForA = getTeamScheduleStatuses(userAId);
            Map<Long, String> statusForB = getTeamScheduleStatuses(userBId);

            // A自身の状態はA自身の行（未回答=UNDECIDED）であり、Bの回答(ATTENDING)が漏れていない。
            assertThat(statusForA.get(scheduleId)).isEqualTo("UNDECIDED");
            assertThat(statusForA.get(scheduleId)).isNotEqualTo("ATTENDING");
            // Bの一覧ではB自身の回答が正しく反映される。
            assertThat(statusForB.get(scheduleId)).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("出欠レコード自体が存在しないユーザーはnull（出欠募集対象外/未生成）")
        void 出欠レコード無しユーザーはnull() throws Exception {
            Long scheduleId = createTeamSchedule("出欠必須予定(未生成)", true);
            // userAにのみレコードを生成。userBには生成しない。
            generateAttendance(scheduleId, userAId);
            respondAttendance(userAId, scheduleId, "ABSENT");

            Map<Long, String> statusForB = getTeamScheduleStatuses(userBId);

            assertThat(statusForB.get(scheduleId)).isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5: 性能（N+1回避）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-5: 性能 — 複数予定でも取り違えず、SQL発行数が件数に比例して増えない")
    class Performance {

        @Test
        @DisplayName("複数予定それぞれに正しいmyAttendanceStatusが付き、SQL発行数は件数非依存")
        void 複数予定でも正しく個別対応しSQL数は非Nplus1() throws Exception {
            // 1件のみのケース（基準値）
            Long soloScheduleId = createTeamSchedule("単独予定", true);
            generateAttendance(soloScheduleId, userAId);
            respondAttendance(userAId, soloScheduleId, "ATTENDING");

            Statistics stats = statisticsCleared();
            getTeamScheduleStatuses(userAId);
            long soloCount = stats.getPrepareStatementCount();

            // 複数件（4件）のケース。ステータスをそれぞれ変えて取り違えが無いことも検証する。
            Long scheduleAttending = createTeamSchedule("複数-ATTENDING", true);
            Long schedulePartial = createTeamSchedule("複数-PARTIAL", true);
            Long scheduleAbsent = createTeamSchedule("複数-ABSENT", true);
            Long scheduleNoRecord = createTeamSchedule("複数-未生成", false);
            generateAttendance(scheduleAttending, userAId);
            generateAttendance(schedulePartial, userAId);
            generateAttendance(scheduleAbsent, userAId);
            respondAttendance(userAId, scheduleAttending, "ATTENDING");
            respondAttendance(userAId, schedulePartial, "PARTIAL");
            respondAttendance(userAId, scheduleAbsent, "ABSENT");

            Statistics stats2 = statisticsCleared();
            Map<Long, String> statusById = getTeamScheduleStatuses(userAId);
            long multiCount = stats2.getPrepareStatementCount();

            // 正しさ: 各予定が自分自身のステータスに個別対応する（取り違えなし）。
            assertThat(statusById.get(soloScheduleId)).isEqualTo("ATTENDING");
            assertThat(statusById.get(scheduleAttending)).isEqualTo("ATTENDING");
            assertThat(statusById.get(schedulePartial)).isEqualTo("PARTIAL");
            assertThat(statusById.get(scheduleAbsent)).isEqualTo("ABSENT");
            assertThat(statusById.get(scheduleNoRecord)).isNull();

            // 性能: 対象件数が1→5(solo含む)件に増えても、
            // ScheduleAttendanceRepository#findByScheduleIdInAndUserId は IN句1本のバッチのため
            // 発行SQL数はほぼ変化しない（N+1なら対象件数に比例して増加するはず）。
            assertThat(multiCount)
                    .as("一覧件数が増えてもSQL発行数はほぼ一定であるべし（findByScheduleIdInAndUserIdは常に1本）")
                    .isLessThanOrEqualTo(soloCount + 2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long createTeamSchedule(String title, boolean attendanceRequired) {
        ScheduleEntity schedule = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .startAt(LocalDateTime.of(2026, 4, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(attendanceRequired)
                .createdBy(userAId)
                .build());
        em.flush();
        em.clear();
        return schedule.getId();
    }

    private Long createOrgSchedule(String title, boolean attendanceRequired) {
        ScheduleEntity schedule = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgId)
                .title(title)
                .startAt(LocalDateTime.of(2026, 4, 11, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 11, 12, 0))
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(attendanceRequired)
                .createdBy(userAId)
                .build());
        em.flush();
        em.clear();
        return schedule.getId();
    }

    /**
     * 出欠レコードを UNDECIDED で 1 件生成する
     * （{@code ScheduleAttendanceService#generateAttendanceRecords} 相当の最小再現。
     * 出欠募集の全経路（イベントリスナー等）を通す必要はなく、レコード存在有無だけがテスト対象）。
     */
    private void generateAttendance(Long scheduleId, Long userId) {
        attendanceRepository.save(ScheduleAttendanceEntity.builder()
                .scheduleId(scheduleId)
                .userId(userId)
                .status(AttendanceStatus.UNDECIDED)
                .isProxyInput(false)
                .build());
        em.flush();
        em.clear();
    }

    private void respondAttendance(Long userId, Long scheduleId, String status) throws Exception {
        setAuth(userId);
        mockMvc.perform(patch("/api/v1/schedules/{scheduleId}/responses", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", status))))
                .andExpect(status().isOk());
    }

    private Map<Long, String> getTeamScheduleStatuses(Long viewerUserId) throws Exception {
        setAuth(viewerUserId);
        MvcResult result = mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules", teamSlug)
                        .param("from", FROM.format(ISO))
                        .param("to", TO.format(ISO)))
                .andExpect(status().isOk())
                .andReturn();
        return extractStatusById(result);
    }

    private Map<Long, String> getOrgScheduleStatuses(Long viewerUserId) throws Exception {
        setAuth(viewerUserId);
        MvcResult result = mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules", orgSlug)
                        .param("from", FROM.format(ISO))
                        .param("to", TO.format(ISO)))
                .andExpect(status().isOk())
                .andReturn();
        return extractStatusById(result);
    }

    private Map<Long, String> extractStatusById(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = root.get("data");
        Map<Long, String> statusById = new HashMap<>();
        for (JsonNode node : data) {
            Long id = node.get("id").asLong();
            JsonNode statusNode = node.get("myAttendanceStatus");
            statusById.put(id, (statusNode == null || statusNode.isNull()) ? null : statusNode.asText());
        }
        return statusById;
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'MYATTEND', 'テスト', 'MYATTEND テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
