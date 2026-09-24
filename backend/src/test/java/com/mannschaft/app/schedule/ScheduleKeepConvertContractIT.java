package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.event.ScheduleKeepConvertedEvent;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.17 キープ（日付未定の予定）変換・状態遷移 API 契約テスト（試練 Wave2）。
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §5.3（状態×操作の全セル表）・
 * §4.5（変換仕様）・§9.2〜9.4 受け入れ条件。
 * Wave1（{@link ScheduleKeepTeamContractIT}）の基盤・CRUD・SUPPORTER遮断・IDOR基本を金型に、
 * convert / revert / archive / restore の状態遷移全セルと reorder / by-schedule を追加する。</p>
 *
 * <p>プロダクションコード側は本試練の時点で {@code convert}・{@code reorder}・{@code by-schedule}
 * のエンドポイントが未実装であり、archive/restore/revert も「変換先 schedules の後始末」を
 * 実装していない（Wave1 コメント参照）。よって本クラスの多くのテストは red で正常である。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.17 キープ 変換・状態遷移 API 契約テスト（試練 Wave2）")
class ScheduleKeepConvertContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleKeepRepository scheduleKeepRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleAttendanceRepository scheduleAttendanceRepository;

    @PersistenceContext
    private EntityManager em;

    /**
     * #2990 L8 以降、キープ変換の通知は業務コミット後（{@code AFTER_COMMIT} リスナー）に配送される。
     * 本クラスは {@code @Transactional} でコミットしないため通知行は原理的に生まれない。
     * よって本クラスが負う契約は「業務トランザクション内で配送イベントを publish すること」であり、
     * 配送内容（受信者・可視性・fan-out）は {@code ScheduleKeepNotificationServiceTest} と
     * {@code ScheduleKeepConvertedNotificationListenerTest} が受け持つ。
     */
    @Autowired
    private ApplicationEvents applicationEvents;

    private Long teamId;
    private Long otherTeamId;
    private Long orgId;
    private String teamSlug;
    private String otherTeamSlug;
    private String orgSlug;

    private Long memberId;
    private Long adminId;
    private Long otherMemberId;
    private Long supporterId;
    private Long otherTeamMemberId;
    private Long personalUserId;
    private Long otherPersonalUserId;

    @BeforeEach
    void setUp() {
        long suffix = System.nanoTime() % 1_000_000L;
        teamSlug = "kc-" + suffix;
        otherTeamSlug = "kc2-" + suffix;
        orgSlug = "kco-" + suffix;
        teamId = insertTeam("キープ変換試練チーム", teamSlug);
        otherTeamId = insertTeam("キープ変換試練別チーム", otherTeamSlug);
        orgId = insertOrganization("キープ変換試練組織", orgSlug);

        memberId = insertUser("keepconv-member@example.com");
        adminId = insertUser("keepconv-admin@example.com");
        otherMemberId = insertUser("keepconv-other-member@example.com");
        supporterId = insertUser("keepconv-supporter@example.com");
        otherTeamMemberId = insertUser("keepconv-other-team-member@example.com");
        personalUserId = insertUser("keepconv-personal@example.com");
        otherPersonalUserId = insertUser("keepconv-other-personal@example.com");

        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);

        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);

        MembershipTestHelper.insertMembership(em, otherTeamMemberId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-06 / AC-06b / AC-06c / AC-07（正常変換）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("正常変換（convert）")
    class Convert {

        @Test
        @DisplayName("AC-06: KEPTにconvert（startAtのみ）で200。キープはSCHEDULED・convertedScheduleIdが非null。"
                + "チームカレンダーに同じタイトルの予定が実際に現れる")
        void AC06_KEPTのconvertで200_SCHEDULEDになり予定が実際に作られる() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "夏合宿", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            String body = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "startAt", "2026-08-15T00:00:00", "allDay", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.keep.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.data.keep.convertedScheduleId").isNotEmpty())
                    .andExpect(jsonPath("$.data.schedule.title").value("夏合宿"))
                    .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleDto = (Map<String, Object>) data.get("schedule");
            Number scheduleId = (Number) scheduleDto.get("id");

            List<ScheduleEntity> created = scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                            teamId, LocalDateTime.of(2026, 8, 14, 0, 0), LocalDateTime.of(2026, 8, 16, 0, 0));
            assertThat(created).anyMatch(s -> s.getId().equals(scheduleId.longValue())
                    && "夏合宿".equals(s.getTitle()));
        }

        @Test
        @DisplayName("AC-06b: 変換で作られた予定はattendance_required=false・event_type=OTHER・"
                + "status=SCHEDULED・created_by=変換操作者・スコープ列がキープと一致する")
        void AC06b_変換先予定の既定値() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "既定値検証キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            // 変換操作者は作成者(memberId)ではなくADMIN(adminId)にして「操作者」であることを判別可能にする。
            setAuthentication(adminId);
            String body = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "startAt", "2026-09-01T00:00:00", "allDay", true))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleDto = (Map<String, Object>) data.get("schedule");
            Long scheduleId = ((Number) scheduleDto.get("id")).longValue();

            ScheduleEntity created = scheduleRepository.findById(scheduleId).orElseThrow();
            assertThat(created.getAttendanceRequired()).isFalse();
            assertThat(created.getEventType()).isEqualTo(EventType.OTHER);
            assertThat(created.getStatus()).isEqualTo(ScheduleStatus.SCHEDULED);
            assertThat(created.getCreatedBy()).isEqualTo(adminId);
            assertThat(created.getTeamId()).isEqualTo(teamId);
        }

        @Test
        @DisplayName("AC-06c: 変換先の予定はvisibility=MEMBERS_ONLY・min_view_role=MEMBER_PLUSの固定値を持ち、"
                + "チーム既定が緩くても継承しない。変換前に見られなかったSUPPORTERは変換後の予定も見られない")
        void AC06c_変換先の可視性は固定値でSUPPORTERは見られない() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "可視性固定検証キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            String body = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "startAt", "2026-09-05T00:00:00", "allDay", true))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleDto = (Map<String, Object>) data.get("schedule");
            Long scheduleId = ((Number) scheduleDto.get("id")).longValue();

            ScheduleEntity created = scheduleRepository.findById(scheduleId).orElseThrow();
            assertThat(created.getVisibility()).isEqualTo(ScheduleVisibility.MEMBERS_ONLY);
            assertThat(created.getMinViewRole()).isEqualTo(MinViewRole.MEMBER_PLUS);

            // 変換前にキープを見られなかったSUPPORTERは、変換後の予定も見られないこと（403。
            // PR #2705でmin_view_roleが可視性基盤の生きた軸になり、閲覧不可の裁定は404から403に是正された）。
            setAuthentication(supporterId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}", teamSlug, scheduleId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AC-07: 変換後もキープのレコードは消えていない。status=ALLにSCHEDULEDとして残り、"
                + "convertedScheduleIdから予定を辿れる")
        void AC07_変換後もキープは残りconvertedScheduleIdで辿れる() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "残存確認キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "startAt", "2026-09-10T00:00:00", "allDay", true))))
                    .andExpect(status().isOk());

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .param("status", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='" + keep.getId() + "')].status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.data[?(@.id=='" + keep.getId() + "')].convertedScheduleId").isNotEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-09 / AC-09b / AC-09c（逆引き）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("逆引き（by-schedule）")
    class ReverseLookup {

        @Test
        @DisplayName("AC-09: GET .../schedule-keeps/by-schedule/{scheduleId}が由来キープを返す。"
                + "キープ由来でない予定は404。schedulesの応答にoriginKeepIdは存在しない")
        void AC09_逆引きは由来キープを返しキープ由来でない予定は404() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "変換由来の予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "変換由来の予定", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);

            ScheduleEntity notConverted = saveSchedule(teamId, "普通に作られた予定", null);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/by-schedule/{scheduleId}",
                            teamSlug, converted.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(keep.getId().toString()));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/by-schedule/{scheduleId}",
                            teamSlug, notConverted.getId()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}", teamSlug, converted.getId()))
                    .andExpect(jsonPath("$.data.originKeepId").doesNotExist());
        }

        @Test
        @DisplayName("AC-09b: 一覧のキープ解決は行数に比例してクエリが増えない"
                + "（Hibernate統計で実測。5件と20件でSQL発行数がほぼ同じ）")
        void AC09b_一覧の解決はN1回避() throws Exception {
            Statistics statistics = em.getEntityManagerFactory()
                    .unwrap(SessionFactory.class).getStatistics();
            boolean wasEnabled = statistics.isStatisticsEnabled();
            statistics.setStatisticsEnabled(true);
            try {
                setAuthentication(memberId);

                createConvertedKeeps(5, "N1-a");
                long queriesFor5 = measureListQueryCount();

                createConvertedKeeps(15, "N1-b");
                long queriesFor20 = measureListQueryCount();

                // 行ごとに引く実装なら 15 行増でクエリは数十発増える（変換先・slug・表示名で行あたり3発）。
                // 一括解決なら増分は 0（IN 句の要素が増えるだけ）。実測の揺らぎに 2 発だけ余裕を持たせる。
                assertThat(queriesFor20 - queriesFor5)
                        .as("キープ一覧のSQL発行数が行数に比例して増えている（§10.3 一括解決の違反）。"
                                + "5件=%d発, 20件=%d発", queriesFor5, queriesFor20)
                        .isLessThanOrEqualTo(2L);
            } finally {
                statistics.setStatisticsEnabled(wasEnabled);
            }
        }

        /** SCHEDULED（変換済み）のキープを count 件作る。変換先の予定も1件ずつ作る。 */
        private void createConvertedKeeps(int count, String prefix) {
            for (int i = 0; i < count; i++) {
                ScheduleEntity converted = saveSchedule(teamId, prefix + "-予定" + i, null);
                ScheduleKeepEntity keep = saveKeep(teamId, memberId, prefix + "-キープ" + i,
                        ScheduleKeepStatus.SCHEDULED, i);
                keep.setConvertedScheduleId(converted.getId());
                scheduleKeepRepository.save(keep);
            }
            em.flush();
            em.clear();
        }

        /** 一覧APIを1回叩くのに要した JDBC ステートメント数を測る。 */
        private long measureListQueryCount() throws Exception {
            Statistics statistics = em.getEntityManagerFactory()
                    .unwrap(SessionFactory.class).getStatistics();
            long before = statistics.getPrepareStatementCount();
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .param("status", "SCHEDULED")
                            .param("size", "50"))
                    .andExpect(status().isOk());
            return statistics.getPrepareStatementCount() - before;
        }

        @Test
        @DisplayName("AC-09c: 変換先に繰り返しを設定して子が展開されたとき、逆引きは親のみに紐づき子は404")
        void AC09c_繰り返し展開の子は逆引き対象外() throws Exception {
            ScheduleEntity parent = saveSchedule(teamId, "繰り返し親", null);
            ScheduleEntity child = saveSchedule(teamId, "繰り返し子", parent.getId());
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "繰り返し親", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(parent.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/by-schedule/{scheduleId}",
                            teamSlug, parent.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(keep.getId().toString()));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/by-schedule/{scheduleId}",
                            teamSlug, child.getId()))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10 / AC-10b / AC-10c / AC-10d / AC-10e（archive / restore の全セル）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("archive / restore の状態遷移全セル")
    class ArchiveRestore {

        @Test
        @DisplayName("AC-10: archiveでARCHIVEDになり既定一覧から消える。KEPT由来ならrestoreでKEPTに戻り再び現れる")
        void AC10_KEPT由来のarchive_restoreはKEPTに戻る() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "KEPT由来アーカイブ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("KEPT"));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("AC-10b: SCHEDULED→archive→restoreは200でSCHEDULEDに戻る"
                + "（convertedScheduleIdは保持されたまま・409にはならない）。変換先の予定も消えていない")
        void AC10b_SCHEDULED由来のarchive_restoreはSCHEDULEDに戻り予定は消えない() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC10b予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC10bキープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
                    .andExpect(jsonPath("$.data.convertedScheduleId").value(converted.getId().intValue()));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.data.convertedScheduleId").value(converted.getId().intValue()));

            assertThat(scheduleRepository.findById(converted.getId())).isPresent();
            assertThat(scheduleRepository.findById(converted.getId()).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("AC-10c: AC-10bの直後にconvertを叩くと409 SCHEDULE_KEEP_006。予定は1件のまま増えない")
        void AC10c_restore後の再convertは409で二重生成しない() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC10c予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC10cキープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-10-01T00:00:00"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_006"));

            long countByTitle = scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                            teamId, LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0))
                    .stream().filter(s -> "AC10c予定".equals(s.getTitle())).count();
            assertThat(countByTitle).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-10d: ARCHIVEDに再archiveは200no-op。KEPTにrestoreは200no-op。"
                + "SCHEDULEDにrestoreは200no-op。KEPTにrevertは409 SCHEDULE_KEEP_009")
        void AC10d_冪等noop群() throws Exception {
            ScheduleKeepEntity archived = saveKeep(teamId, memberId, "AC10d-archived", ScheduleKeepStatus.ARCHIVED, 0);
            ScheduleKeepEntity kept = saveKeep(teamId, memberId, "AC10d-kept", ScheduleKeepStatus.KEPT, 0);
            ScheduleEntity converted = saveSchedule(teamId, "AC10d予定", null);
            ScheduleKeepEntity scheduled = saveKeep(teamId, memberId, "AC10d-scheduled", ScheduleKeepStatus.SCHEDULED, 0);
            scheduled.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(scheduled);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            teamSlug, archived.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, kept.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("KEPT"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, scheduled.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            teamSlug, kept.getId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_009"));
        }

        @Test
        @DisplayName("AC-10e: ARCHIVEDのキープをDELETEできる（200・論理削除）")
        void AC10e_ARCHIVEDのDELETEは論理削除で成功() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC10e-archived", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertThat(scheduleKeepRepository.findById(keep.getId())).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11 / AC-11b（revert）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("revert（変換の取消）")
    class Revert {

        @Test
        @DisplayName("AC-11: SCHEDULEDにrevertでKEPTに戻り、変換先の予定がカレンダーから消える（論理削除）。"
                + "convertedScheduleIdはnullになる")
        void AC11_revertでKEPTに戻り変換先予定は論理削除される() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC11予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC11キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("KEPT"))
                    .andExpect(jsonPath("$.data.convertedScheduleId").doesNotExist());

            em.flush();
            em.clear();
            assertThat(scheduleRepository.findById(converted.getId())).isEmpty();
        }

        @Test
        @DisplayName("AC-11b: 変換先の予定を先に削除した後でrevertすると200が返り、"
                + "キープはKEPT・convertedScheduleId=nullになる（エラーにしない）")
        void AC11b_変換先が先に削除済みでもrevertは冪等に200() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC11b予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC11bキープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();

            // 変換先を先に論理削除しておく。
            em.createNativeQuery("UPDATE schedules SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", converted.getId())
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("KEPT"))
                    .andExpect(jsonPath("$.data.convertedScheduleId").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-14c / AC-14d / AC-14e / AC-14f（IDOR・操作系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR防御（操作系・スコープ跨ぎ）")
    class IdorOperations {

        @Test
        @DisplayName("AC-14c: 別チームのMEMBERがconvertすると404。他チームのキープが予定化されない")
        void AC14c_別チームMEMBERのconvertは404で予定化されない() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "他チームから変換できないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            otherTeamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-08-15T00:00:00"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            em.flush();
            em.clear();
            assertThat(scheduleKeepRepository.findById(keep.getId()).orElseThrow().getStatus())
                    .isEqualTo(ScheduleKeepStatus.KEPT);
        }

        @Test
        @DisplayName("AC-14d: 別チームのMEMBERがrevert/archive/restoreするといずれも404")
        void AC14d_別チームMEMBERの状態遷移操作は404() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC14d予定", null);
            ScheduleKeepEntity scheduled = saveKeep(teamId, memberId, "AC14d-scheduled", ScheduleKeepStatus.SCHEDULED, 0);
            scheduled.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(scheduled);
            ScheduleKeepEntity kept = saveKeep(teamId, memberId, "AC14d-kept", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity archived = saveKeep(teamId, memberId, "AC14d-archived", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            otherTeamSlug, scheduled.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            otherTeamSlug, kept.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            otherTeamSlug, archived.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-14e: orderedIdsに他スコープのキープIDを混ぜると404。自スコープ分の並び順も変更されない（部分適用しない）")
        void AC14e_reorderに他スコープIDを混ぜると404で自スコープも据え置き() throws Exception {
            ScheduleKeepEntity own1 = saveKeep(teamId, memberId, "AC14e-own1", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity own2 = saveKeep(teamId, memberId, "AC14e-own2", ScheduleKeepStatus.KEPT, 10);
            ScheduleKeepEntity foreign = saveKeep(otherTeamId, otherTeamMemberId, "AC14e-foreign", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("orderedIds", List.of(
                                            own2.getId().toString(), own1.getId().toString(), foreign.getId().toString())))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            em.flush();
            em.clear();
            assertThat(scheduleKeepRepository.findById(own1.getId()).orElseThrow().getSortOrder()).isEqualTo(0);
            assertThat(scheduleKeepRepository.findById(own2.getId()).orElseThrow().getSortOrder()).isEqualTo(10);
        }

        @Test
        @DisplayName("AC-14f: チームのパスで組織キープ／個人キープのIDを指定すると404")
        void AC14f_チームパスで組織_個人キープIDを指定すると404() throws Exception {
            ScheduleKeepEntity orgKeep = saveOrgKeep(orgId, memberId, "組織キープ", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity personalKeep = savePersonalKeep(personalUserId, "個人キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, orgKeep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, personalKeep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15b（変換はMEMBER全員に開放）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("権限（convertはMEMBER全員に開放）")
    class ConvertPermission {

        @Test
        @DisplayName("AC-15b: 作成者でもADMINでもないMEMBERのconvertは200で成功する。"
                + "かつ通知配送イベントが業務トランザクション内で publish される")
        void AC15b_非作成者非ADMINでもconvertは200で配送イベントがpublishされる() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC15b-キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-09-20T00:00:00"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.keep.status").value("SCHEDULED"));

            // 業務TX内の契約は「配送イベントを publish すること」まで。実配送は AFTER_COMMIT に移った。
            assertThat(applicationEvents.stream(ScheduleKeepConvertedEvent.class))
                    .as("convert は変換されたキープ ID を載せた配送イベントを業務TX内で publish する")
                    .anySatisfy(event -> {
                        assertThat(event.keepId()).isEqualTo(keep.getId());
                        assertThat(event.convertedScheduleId()).isNotNull();
                        assertThat(event.actorUserId()).isEqualTo(otherMemberId);
                    });
        }

        /**
         * #2990 L8 で変換通知の配送が {@code AFTER_COMMIT} リスナーへ移ったため、
         * {@code @Transactional} な本クラスでは通知行が原理的に 0 件になる。
         * 「0 件であること」をここで測ると<b>常に通る偽の緑</b>になるので測らない。
         * 降格作成者へ直送しないこと自体は
         * {@code ScheduleKeepNotificationServiceTest} の AC-4
         * （作成者に閲覧権が無い場合 creator 直送はスキップする）が受け持つ。
         * 本テストはその前提となる「降格作成者からキープが 404 で秘匿されていること」と
         * 「それでも他 MEMBER の convert は成立すること」を守る。
         */
        @Test
        @DisplayName("SUPPORTERへ降格した作成者からキープは404で秘匿され、それでも他MEMBERのconvertは成立する")
        void 降格した作成者にはキープが秘匿されたままconvertは成立する() throws Exception {
            // supporterId が作ったキープ。その後 SUPPORTER になった（＝キープが見えなくなった）状況を模す。
            ScheduleKeepEntity keep = saveKeep(teamId, supporterId, "降格作成者のキープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            // 前提の確認: 作成者はもうこのキープを見られない（404 で秘匿されている）。
            setAuthentication(supporterId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isNotFound());

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-09-21T00:00:00"))))
                    .andExpect(status().isOk());

        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-17 / AC-18 / AC-18b / AC-19 / AC-20（異常系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("異常系（再変換・編集ロック・出欠ブロック）")
    class Abnormal {

        @Test
        @DisplayName("AC-17: 既にSCHEDULEDのキープに再度convertすると409 SCHEDULE_KEEP_006")
        void AC17_SCHEDULEDへの再convertは409() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC17予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC17キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-10-05T00:00:00"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_006"));
        }

        @Test
        @DisplayName("AC-18: SCHEDULEDにtitleをPATCHすると409 SCHEDULE_KEEP_007。memoのPATCHは200")
        void AC18_SCHEDULEDのtitlePATCHは409_memoは200() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC18キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(saveSchedule(teamId, "AC18予定", null).getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "改名"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_007"));

            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("memo", "追記メモ"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memo").value("追記メモ"));
        }

        @Test
        @DisplayName("AC-18b: ARCHIVEDへのPATCHはmemoでも409 SCHEDULE_KEEP_007")
        void AC18b_ARCHIVEDへのmemoPATCHも409() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC18bキープ", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("memo", "アーカイブ後メモ"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_007"));
        }

        @Test
        @DisplayName("AC-19: ARCHIVED（由来を問わず）に直接convertすると409 SCHEDULE_KEEP_009（_006ではない）")
        void AC19_ARCHIVEDへの直接convertは409_009() throws Exception {
            ScheduleKeepEntity archivedFromKept = saveKeep(
                    teamId, memberId, "AC19-KEPT由来", ScheduleKeepStatus.ARCHIVED, 0);
            ScheduleEntity converted = saveSchedule(teamId, "AC19予定", null);
            ScheduleKeepEntity archivedFromScheduled = saveKeep(
                    teamId, memberId, "AC19-SCHEDULED由来", ScheduleKeepStatus.ARCHIVED, 0);
            archivedFromScheduled.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(archivedFromScheduled);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, archivedFromKept.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-11-01T00:00:00"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_009"));

            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert",
                            teamSlug, archivedFromScheduled.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("startAt", "2026-11-01T00:00:00"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_009"));
        }

        @Test
        @DisplayName("AC-20: 変換先に出欠回答がある状態でrevertすると409 SCHEDULE_KEEP_008。予定は消えない")
        void AC20_出欠回答があるとrevertは409で予定は消えない() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC20予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC20キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            scheduleAttendanceRepository.save(ScheduleAttendanceEntity.builder()
                    .scheduleId(converted.getId())
                    .userId(memberId)
                    .status(com.mannschaft.app.schedule.AttendanceStatus.ATTENDING)
                    .build());
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            teamSlug, keep.getId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_008"));

            em.flush();
            em.clear();
            assertThat(scheduleRepository.findById(converted.getId())).isPresent();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-20b / AC-20c（reorder異常系・組織／個人認可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reorder異常系・組織/個人スコープの認可")
    class ReorderAndScopeAuthz {

        @Test
        @DisplayName("AC-05: orderedIdsの順に並び替わり、リクエストに含めなかったキープのsortOrderは変化しない")
        void AC05_reorderで指定順に並び替わり非対象は据え置き() throws Exception {
            // 初期の sort_order は 0/1/2（＝a, b, c の順）。これを c, a, b に並べ替える。
            // untouched は 99 を与え、並び替え後も末尾に留まる（＝据え置きが観測できる）位置に置く。
            ScheduleKeepEntity keepA = saveKeep(teamId, memberId, "AC05-a", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity keepB = saveKeep(teamId, memberId, "AC05-b", ScheduleKeepStatus.KEPT, 1);
            ScheduleKeepEntity keepC = saveKeep(teamId, memberId, "AC05-c", ScheduleKeepStatus.KEPT, 2);
            ScheduleKeepEntity untouched = saveKeep(teamId, memberId, "AC05-untouched", ScheduleKeepStatus.KEPT, 99);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "orderedIds", List.of(
                                            keepC.getId().toString(),
                                            keepA.getId().toString(),
                                            keepB.getId().toString())))))
                    .andExpect(status().isOk());

            // 一覧の並びが指定どおり（c, a, b）で、含めなかった untouched が末尾に残ること。
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(4))
                    .andExpect(jsonPath("$.data[0].title").value("AC05-c"))
                    .andExpect(jsonPath("$.data[1].title").value("AC05-a"))
                    .andExpect(jsonPath("$.data[2].title").value("AC05-b"))
                    .andExpect(jsonPath("$.data[3].title").value("AC05-untouched"));

            em.flush();
            em.clear();
            // リクエストに含めなかったキープの sort_order は変化しない（§4.4.1 部分並び替え）。
            assertThat(scheduleKeepRepository.findById(untouched.getId()).orElseThrow().getSortOrder())
                    .isEqualTo(99);
        }

        @Test
        @DisplayName("AC-20b: orderedIdsに重複IDは400 SCHEDULE_KEEP_012")
        void AC20b_reorder重複IDは400() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC20b-1", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "orderedIds", List.of(keep.getId().toString(), keep.getId().toString())))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_012"));
        }

        @Test
        @DisplayName("AC-20b: orderedIdsにARCHIVEDのキープを混入すると400 SCHEDULE_KEEP_012")
        void AC20b_reorderにARCHIVED混入は400() throws Exception {
            ScheduleKeepEntity kept = saveKeep(teamId, memberId, "AC20b-kept", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity archived = saveKeep(teamId, memberId, "AC20b-archived", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "orderedIds", List.of(kept.getId().toString(), archived.getId().toString())))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_012"));
        }

        @Test
        @DisplayName("AC-20b: orderedIdsに削除済みキープのIDを含めると404")
        void AC20b_reorderに削除済みIDは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC20b-deleted", ScheduleKeepStatus.KEPT, 0);
            UUID deletedId = keep.getId();
            keep.setDeletedAt(LocalDateTime.now());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "orderedIds", List.of(deletedId.toString())))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-20b: 件数がスコープ上限を超過すると400")
        void AC20b_reorder件数上限超過は400() throws Exception {
            List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 301; i++) {
                ids.add(saveKeep(teamId, memberId, "AC20b-limit-" + i, ScheduleKeepStatus.KEPT, i).getId().toString());
            }
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/reorder", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("orderedIds", ids))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_012"));
        }

        @Test
        @DisplayName("AC-20c: 組織スコープ 非メンバーの一覧・単体GETは404")
        void AC20c_組織スコープ非メンバーは404() throws Exception {
            ScheduleKeepEntity orgKeep = saveOrgKeep(orgId, memberId, "組織AC20cキープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedule-keeps", orgSlug))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedule-keeps/{keepId}",
                            orgSlug, orgKeep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-20c: 個人スコープ 他人のキープIDを/me/schedule-keeps/{keepId}でGETすると404")
        void AC20c_個人スコープ他人のキープは404() throws Exception {
            ScheduleKeepEntity own = savePersonalKeep(personalUserId, "本人の個人キープ", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity other = savePersonalKeep(otherPersonalUserId, "他人の個人キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(personalUserId);
            mockMvc.perform(get("/api/v1/me/schedule-keeps/{keepId}", other.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            mockMvc.perform(get("/api/v1/me/schedule-keeps/{keepId}", own.getId()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-04（個人スコープの隔離）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個人スコープの隔離")
    class PersonalIsolation {

        @Test
        @DisplayName("AC-04: 個人スコープに作成したキープは、チーム一覧・組織一覧のいずれにも現れない")
        void AC04_個人キープはチーム一覧_組織一覧に現れない() throws Exception {
            savePersonalKeep(personalUserId, "AC04-個人キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .param("status", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.title=='AC04-個人キープ')]").isEmpty());

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedule-keeps", orgSlug)
                            .param("status", "ALL"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-25（件数上限）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数上限")
    class Limit {

        @Test
        @DisplayName("AC-25: 上限到達後の作成は409 SCHEDULE_KEEP_010。上限-1件目までは201。"
                + "ARCHIVED/SCHEDULEDは件数に含まれない")
        void AC25_KEPT件数上限で409_ARCHIVED_SCHEDULEDは含まれない() throws Exception {
            // チーム上限は300件（§10.1）。ARCHIVED/SCHEDULEDを大量に混ぜても上限に影響しないことを示す。
            for (int i = 0; i < 299; i++) {
                saveKeep(teamId, memberId, "AC25-KEPT-" + i, ScheduleKeepStatus.KEPT, i);
            }
            for (int i = 0; i < 50; i++) {
                saveKeep(teamId, memberId, "AC25-ARCHIVED-" + i, ScheduleKeepStatus.ARCHIVED, i);
            }
            em.flush();
            em.clear();

            setAuthentication(memberId);
            // 300件目（上限ちょうど）は201で通る。
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "AC25-300件目"))))
                    .andExpect(status().isCreated());

            // 301件目は409。
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "AC25-301件目"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_010"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-26（変換先の消滅）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("変換先schedulesの消滅")
    class ConvertedScheduleGone {

        @Test
        @DisplayName("AC-26: 変換先を論理削除するとconvertedScheduleState=DELETED")
        void AC26_変換先論理削除でDELETED() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC26-削除予定", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC26-削除キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.createNativeQuery("UPDATE schedules SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", converted.getId())
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.convertedScheduleState").value("DELETED"));
        }

        @Test
        @DisplayName("AC-26: 変換先をCANCELLEDにするとconvertedScheduleState=CANCELLED")
        void AC26_変換先CANCELLEDでCANCELLED() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "AC26-中止予定", null);
            converted = scheduleRepository.save(converted.toBuilder().status(ScheduleStatus.CANCELLED).build());
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "AC26-中止キープ", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.convertedScheduleState").value("CANCELLED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PATCH ボディの型検証（§7.1: クライアント入力起因の500を作らない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH candidateDates の型検証")
    class PatchCandidateDatesTypeCheck {

        @Test
        @DisplayName("candidateDatesが配列でなく文字列でも500にならず400 SCHEDULE_KEEP_004")
        void 配列でない文字列は400() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "型検証キープ1", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateDates\":\"2026-08-15\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_004"));
        }

        @Test
        @DisplayName("candidateDatesの要素が数値でも500にならず400 SCHEDULE_KEEP_004")
        void 要素が数値は400() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "型検証キープ2", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateDates\":[20260815]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_004"));
        }

        @Test
        @DisplayName("11件かつ1件が形式不正でも、件数超過の SCHEDULE_KEEP_003 が返る（AC-13の判定順）")
        void 件数超過は形式不正より先に判定される() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "型検証キープ3", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            List<String> dates = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                dates.add(String.format("2026-08-%02d", i));
            }
            dates.add("2026/08/20");

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("candidateDates", dates))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_003"));
        }

        @Test
        @DisplayName("titleは前後の空白をtrimして保存され、trim後200文字以内なら通る")
        void titleはtrimして保存される() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "trim検証キープ", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "  合宿の相談  "))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("合宿の相談"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // §4.4: SCHEDULED のキープは変換先 schedules.title を正として返す
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("変換後のタイトル同期")
    class ConvertedTitleSync {

        @Test
        @DisplayName("変換先の予定名を変更すると、キープの単体GET・一覧の title も追随する"
                + "（SCHEDULEDはtitleのPATCHが409で直せないため、旧題目が残ると袋小路になる）")
        void 変換先の予定名変更にキープのtitleが追随する() throws Exception {
            ScheduleEntity converted = saveSchedule(teamId, "旧タイトル", null);
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "旧タイトル", ScheduleKeepStatus.SCHEDULED, 0);
            keep.setConvertedScheduleId(converted.getId());
            scheduleKeepRepository.save(keep);
            em.flush();

            em.createNativeQuery("UPDATE schedules SET title = :t WHERE id = :id")
                    .setParameter("t", "新タイトル")
                    .setParameter("id", converted.getId())
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("新タイトル"));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .param("status", "SCHEDULED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id=='" + keep.getId() + "')].title").value("新タイトル"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private ScheduleKeepEntity saveKeep(Long teamId, Long createdBy, String title,
                                         ScheduleKeepStatus status, int sortOrder) {
        return scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .teamId(teamId)
                .title(title)
                .status(status)
                .sortOrder(sortOrder)
                .createdBy(createdBy)
                .build());
    }

    private ScheduleKeepEntity saveOrgKeep(Long orgId, Long createdBy, String title,
                                            ScheduleKeepStatus status, int sortOrder) {
        return scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .organizationId(orgId)
                .title(title)
                .status(status)
                .sortOrder(sortOrder)
                .createdBy(createdBy)
                .build());
    }

    private ScheduleKeepEntity savePersonalKeep(Long userId, String title,
                                                 ScheduleKeepStatus status, int sortOrder) {
        return scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .userId(userId)
                .title(title)
                .status(status)
                .sortOrder(sortOrder)
                .createdBy(userId)
                .build());
    }

    private ScheduleEntity saveSchedule(Long teamId, String title, Long parentScheduleId) {
        return scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .startAt(LocalDateTime.of(2026, 8, 15, 0, 0))
                .allDay(true)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .parentScheduleId(parentScheduleId)
                .createdBy(memberId)
                .build());
    }

    private void setAuthentication(Long userId) {
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
                                + "VALUES (:email, 'F0317', 'テスト', 'F0317 テスト', 'ACTIVE', "
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
                        "INSERT INTO organizations (name, org_type, visibility, supporter_enabled, version, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
