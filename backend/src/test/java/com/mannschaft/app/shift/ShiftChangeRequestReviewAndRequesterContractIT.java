package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261010-1432 — シフト変更依頼の BE 契約テスト（試練）。
 *
 * <ul>
 *   <li>B: 自己審査の禁止（マスター決裁 2026-10-12。SYSTEM_ADMIN も例外なし。403 {@code SHIFT_045}）</li>
 *   <li>J3a: 公開（PUBLISHED）以降のシフト表への変更依頼の新規作成を 409 で拒否
 *       （{@code docs/features/F03.5_shift/01_db_design.md}「受付対象ステータス」「提出可能期間」）</li>
 *   <li>M: 一覧応答に依頼者の表示名 {@code requesterName}（名前解決は一覧 1 回のバッチ）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261010-1432 変更依頼（自己審査禁止・受付期間・依頼者名）契約テスト（試練）")
class ShiftChangeRequestReviewAndRequesterContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/change-requests";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftChangeRequestRepository changeRequestRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long adminId;          // チーム ADMIN（依頼者にもなる）
    private Long deputyId;         // 別のチーム管理者（DEPUTY_ADMIN）
    private Long memberId;         // 一般メンバー
    private Long sysAdminMemberId; // SYSTEM_ADMIN かつチームメンバー
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("CMP1432 変更依頼 チーム");
        adminId = insertUser("cmp1432-admin@example.com", "管理者 太郎");
        deputyId = insertUser("cmp1432-deputy@example.com", "副管理者 花子");
        memberId = insertUser("cmp1432-member@example.com", "一般 次郎");
        sysAdminMemberId = insertUser("cmp1432-sysadmin@example.com", "システム 管理");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, deputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyId, "DEPUTY_ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, sysAdminMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, sysAdminMemberId, "SYSTEM_ADMIN", null, null);

        scheduleId = insertSchedule(ShiftScheduleStatus.COLLECTING);
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // B: 自己審査の禁止
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B. 自己審査の禁止（PATCH /change-requests/{id}/review）")
    class SelfReview {

        @Test
        @DisplayName("AC-B1: 依頼者本人（チーム管理者）が自分の依頼を審査すると403 SHIFT_045で、status・versionは変わらない")
        void AC_B1_チーム管理者の自己審査は403_SHIFT_045でDB不変() throws Exception {
            Long requestId = insertRequest(adminId);

            setAuth(adminId);
            mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody("ACCEPTED", 0))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_045"));

            em.flush();
            em.clear();
            ShiftChangeRequestEntity after = changeRequestRepository.findById(requestId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ChangeRequestStatus.OPEN);
            assertThat(after.getVersion()).isEqualTo(0L);
            assertThat(after.getReviewerId()).isNull();
        }

        @Test
        @DisplayName("AC-B1: SYSTEM_ADMIN も例外なく、自分の依頼の審査は403 SHIFT_045（却下も同じ）")
        void AC_B1_SYSTEM_ADMINの自己審査も403_SHIFT_045() throws Exception {
            Long requestId = insertRequest(sysAdminMemberId);

            setAuth(sysAdminMemberId);
            mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody("REJECTED", 0))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_045"));

            em.flush();
            em.clear();
            ShiftChangeRequestEntity after = changeRequestRepository.findById(requestId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ChangeRequestStatus.OPEN);
            assertThat(after.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("AC-B2: 別のチーム管理者（DEPUTY_ADMIN）が同じ依頼を審査すると200")
        void AC_B2_別のチーム管理者の審査は200() throws Exception {
            Long requestId = insertRequest(adminId);

            setAuth(deputyId);
            mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody("ACCEPTED", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reviewInfo.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("AC-B3: version が古く、かつ自己審査のとき、自己審査の403 SHIFT_045 が優先される")
        void AC_B3_version不一致かつ自己審査は403が優先() throws Exception {
            Long requestId = insertRequest(adminId);

            setAuth(adminId);
            mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody("ACCEPTED", 99))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_045"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // J3a: 受付期間外の新規作成拒否
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("J3a. 公開以降のシフト表への変更依頼の新規作成（POST /change-requests）")
    class CreateOutsideWindow {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ShiftScheduleStatus.class, names = {"PUBLISHED", "ARCHIVED"})
        @DisplayName("AC-J3a: PUBLISHED 以降のシフト表への新規作成は409 SHIFT_012で、行は増えない")
        void AC_J3a_公開以降の新規作成は409で行が増えない(ShiftScheduleStatus status) throws Exception {
            Long closedScheduleId = insertSchedule(status);
            em.flush();
            em.clear();
            long before = changeRequestRepository.count();

            setAuth(memberId);
            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(closedScheduleId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_012"));

            em.flush();
            em.clear();
            assertThat(changeRequestRepository.count()).isEqualTo(before);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ShiftScheduleStatus.class, names = {"DRAFT", "COLLECTING", "ADJUSTING"})
        @DisplayName("AC-J3a: 境界（公開前の DRAFT / COLLECTING / ADJUSTING）では従来どおり201")
        void AC_J3a_公開前は201(ShiftScheduleStatus status) throws Exception {
            Long openScheduleId = insertSchedule(status);
            em.flush();
            em.clear();

            setAuth(memberId);
            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(openScheduleId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // M: 依頼者名
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("M. 一覧応答の requesterName")
    class RequesterName {

        @Test
        @DisplayName("AC-M1: 一覧応答のトップレベルに requesterName（表示名）が入る")
        void AC_M1_一覧にrequesterNameが入る() throws Exception {
            Long memberRequest = insertRequest(memberId);
            Long adminRequest = insertRequest(adminId);
            em.flush();
            em.clear();

            setAuth(adminId);
            mockMvc.perform(get(BASE).param("scheduleId", scheduleId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[?(@.id == " + memberRequest + ")].requesterName").value("一般 次郎"))
                    .andExpect(jsonPath("$.data[?(@.id == " + adminRequest + ")].requesterName").value("管理者 太郎"));
        }

        @Test
        @DisplayName("AC-M1: 名前解決は一覧1回のバッチ（2件と6件で発行SQL数が同じ）")
        void AC_M1_名前解決はバッチで件数に依存しない() throws Exception {
            Long smallSchedule = insertSchedule(ShiftScheduleStatus.COLLECTING);
            Long largeSchedule = insertSchedule(ShiftScheduleStatus.COLLECTING);
            insertRequest(smallSchedule, memberId);
            insertRequest(smallSchedule, adminId);
            for (int i = 0; i < 6; i++) {
                Long requester = insertUser("cmp1432-many-" + i + "@example.com", "多数 " + i);
                MembershipTestHelper.insertMembership(em, requester, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                insertRequest(largeSchedule, requester);
            }
            em.flush();
            em.clear();

            setAuth(adminId);
            Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
            stats.setStatisticsEnabled(true);

            stats.clear();
            mockMvc.perform(get(BASE).param("scheduleId", smallSchedule.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].requesterName").isNotEmpty());
            long smallCount = stats.getPrepareStatementCount();

            em.clear();
            stats.clear();
            mockMvc.perform(get(BASE).param("scheduleId", largeSchedule.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(6))
                    .andExpect(jsonPath("$.data[5].requesterName").isNotEmpty());
            long largeCount = stats.getPrepareStatementCount();

            assertThat(largeCount).as("6件でも2件と同じSQL回数（N+1にしない）").isEqualTo(smallCount);
        }

        @Test
        @DisplayName("AC-M2: 名前が解決できない userId（退会者）は requesterName=null")
        void AC_M2_退会者はrequesterNameがnull() throws Exception {
            Long withdrawn = insertUser("cmp1432-withdrawn@example.com", "退会 済み");
            Long requestId = insertRequest(withdrawn);
            em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", withdrawn).executeUpdate();
            em.flush();
            em.clear();

            setAuth(adminId);
            mockMvc.perform(get(BASE).param("scheduleId", scheduleId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(requestId))
                    .andExpect(jsonPath("$.data[0].requesterName").value(nullValue()))
                    .andExpect(jsonPath("$.data[0].requestInfo.requestedBy").value(withdrawn));
        }

        @Test
        @DisplayName("AC-M4: 一般メンバーの一覧は従来どおり自分の依頼だけ（他人の名前は露出しない）")
        void AC_M4_一般メンバーは自分の依頼だけ() throws Exception {
            Long own = insertRequest(memberId);
            insertRequest(adminId);
            em.flush();
            em.clear();

            setAuth(memberId);
            mockMvc.perform(get(BASE).param("scheduleId", scheduleId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(own))
                    .andExpect(jsonPath("$.data[0].requesterName").value("一般 次郎"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long insertRequest(Long requester) {
        return insertRequest(scheduleId, requester);
    }

    private Long insertRequest(Long schedule, Long requester) {
        return changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(schedule)
                .requestType(ChangeRequestType.PRE_CONFIRM_EDIT)
                .requestedBy(requester)
                .reason("CMP1432 試練")
                .build()).getId();
    }

    private Long insertSchedule(ShiftScheduleStatus status) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP1432 試練 " + status)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 7))
                .status(status)
                .createdBy(adminId)
                .build()).getId();
    }

    private static Map<String, Object> reviewBody(String decision, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("reviewComment", "CMP1432 試練");
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> createBody(Long schedule) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", schedule);
        body.put("requestType", "PRE_CONFIRM_EDIT");
        body.put("reason", "CMP1432 試練");
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email, String displayName) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMP', 'テスト', :displayName, 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("displayName", displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
