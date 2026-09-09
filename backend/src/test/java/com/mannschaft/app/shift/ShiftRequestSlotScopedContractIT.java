package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-1143 / 手動シフト作成 戦役A-3 — シフト希望の<b>枠単位化</b>と
 * {@code slotId} の実体整合検証の契約テスト（試練 / red 先行）。
 *
 * <p>設計: {@code docs/features/F03.5_shift/06_manual_authoring.md} §11.5.1 /
 * §11.5.1.1 / §11.5.1.2、受け入れ条件 AC-8 群および AC-4-12。</p>
 *
 * <p><b>現状の実測</b>: {@code ShiftRequestService#submitRequest} の重複判定は
 * {@code findByScheduleIdAndUserIdAndSlotDate}（日付のみ）であり、
 * 同一日に枠が 2 本あっても 1 件しか希望を出せない。さらに {@code slotId} を一切
 * 検証していないため、他チームの枠 ID を自チームの {@code scheduleId} に紐付けられる
 *（BOLA）。本 IT はその両方を固定する。</p>
 *
 * <p>金型: {@code ShiftSlotScopeContractIT}（同ドメイン・同じフィクスチャ流儀）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト希望の枠単位化・slotId 実体整合の契約テスト（試練）")
class ShiftRequestSlotScopedContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftRequestRepository requestRepository;

    @PersistenceContext
    private EntityManager em;

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 3, 2);
    private static final LocalDate OTHER_DATE = LocalDate.of(2026, 3, 3);

    private Long teamAId;
    private Long teamBId;

    private Long memberTeamAId;

    private Long scheduleAId;
    private Long scheduleBId;

    /** TEAM A・3/2 の午前枠（12:00-15:00）。 */
    private Long slotAMorningId;
    /** TEAM A・3/2 の午後枠（16:00-20:00）。同一日 2 本目。 */
    private Long slotAEveningId;
    /** TEAM B（他チーム）の枠。越境検証用。 */
    private Long slotBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("A3 希望枠単位 チームA");
        teamBId = insertTeam("A3 希望枠単位 チームB");

        memberTeamAId = insertUser("a3-request-member-team-a@example.com");
        Long adminTeamAId = insertUser("a3-request-admin-team-a@example.com");

        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);

        scheduleAId = insertCollectingSchedule(teamAId, "A3 チームA 希望収集中", adminTeamAId);
        scheduleBId = insertCollectingSchedule(teamBId, "A3 チームB 希望収集中", adminTeamAId);

        slotAMorningId = insertSlot(scheduleAId, TARGET_DATE, LocalTime.of(12, 0), LocalTime.of(15, 0));
        slotAEveningId = insertSlot(scheduleAId, TARGET_DATE, LocalTime.of(16, 0), LocalTime.of(20, 0));
        slotBId = insertSlot(scheduleBId, TARGET_DATE, LocalTime.of(9, 0), LocalTime.of(17, 0));

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8-01 / AC-8-02 / AC-8-03 — 枠単位の一意性
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 枠単位の希望提出（AC-8-01 / AC-8-02 / AC-8-03）")
    class SlotScopedSubmission {

        @Test
        @DisplayName("AC-8-01 同一日の異なる枠へ2件提出すると両方成功し行が2件になる")
        void 同一日の異なる枠へ2件提出すると両方成功する() throws Exception {
            setAuth(memberTeamAId);

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAMorningId, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAEveningId, TARGET_DATE, "STRONG_REST"))))
                    .andExpect(status().isCreated());

            List<ShiftRequestEntity> stored =
                    requestRepository.findByScheduleIdAndUserId(scheduleAId, memberTeamAId);
            assertThat(stored)
                    .as("同一日でも枠が違えば 2 件の希望が並ぶ（1本＝1人の勤務方式の成立条件）")
                    .hasSize(2)
                    .extracting(ShiftRequestEntity::getSlotId)
                    .containsExactlyInAnyOrder(slotAMorningId, slotAEveningId);
        }

        @Test
        @DisplayName("AC-8-02 同一枠への2件目の希望は409（REQUEST_ALREADY_EXISTS）")
        void 同一枠への2件目の希望は409() throws Exception {
            setAuth(memberTeamAId);

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAMorningId, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAMorningId, TARGET_DATE, "AVAILABLE"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_015"));

            assertThat(requestRepository.findByScheduleIdAndUserId(scheduleAId, memberTeamAId))
                    .as("409 のとき 2 件目は保存されない")
                    .hasSize(1);
        }

        @Test
        @DisplayName("AC-8-03 slotId=null の日単位希望は同一日1件まで（2件目は409）")
        void 日単位希望は同一日1件まで() throws Exception {
            setAuth(memberTeamAId);

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, null, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, null, TARGET_DATE, "WEAK_REST"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_015"));

            assertThat(requestRepository.findByScheduleIdAndUserId(scheduleAId, memberTeamAId))
                    .as("日単位希望の従来規則（同一日 1 件）は保たれる")
                    .hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8-04 — 作成支援ビューの枠ごと希望
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 作成支援ビューの枠ごと希望（AC-8-04）")
    class AuthoringViewSlotPreferences {

        @Test
        @DisplayName("AC-8-04 候補の slotPreferences[] が枠ごとに2要素で返り日付キーで潰れない")
        void 候補のslotPreferencesが枠ごとに返る() throws Exception {
            setAuth(memberTeamAId);

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAMorningId, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAEveningId, TARGET_DATE, "STRONG_REST"))))
                    .andExpect(status().isCreated());

            // 作成支援ビューは管理者の画面。候補は「メンバーごと」に返る。
            setAuth(adminOfTeamA());
            String memberPath = "$.data.candidates[?(@.userId == " + memberTeamAId + ")]";
            mockMvc.perform(get("/api/v1/shifts/schedules/{scheduleId}/authoring-view", scheduleAId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isOk())
                    // 候補ごとの単一 preference ではなく {slotId, preference, note} の配列であること
                    .andExpect(jsonPath(memberPath + ".slotPreferences[*].slotId",
                            org.hamcrest.Matchers.hasSize(2)))
                    .andExpect(jsonPath(memberPath + ".slotPreferences[?(@.slotId == "
                            + slotAMorningId + ")].preference")
                            .value(org.hamcrest.Matchers.hasItem("PREFERRED")))
                    .andExpect(jsonPath(memberPath + ".slotPreferences[?(@.slotId == "
                            + slotAEveningId + ")].preference")
                            .value(org.hamcrest.Matchers.hasItem("STRONG_REST")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8-05 / AC-8-06 / AC-4-12 — slotId の実体整合検証
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. slotId の実体整合検証（AC-8-05 / AC-8-06 / AC-4-12）")
    class SlotIdentityValidation {

        @Test
        @DisplayName("AC-8-05/AC-4-12 他チームの slotId を自チームの scheduleId に紐付けると403で行が増えない")
        void 他チームの枠IDを紐付けると403() throws Exception {
            setAuth(memberTeamAId);
            long before = requestRepository.count();

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotBId, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isForbidden());

            assertThat(requestRepository.count())
                    .as("BOLA: 他チームの枠 ID を紐付けた希望は 1 行も作られない")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("AC-8-06 slotId の枠日付と slotDate が食い違うと400")
        void 枠の日付とslotDateが食い違うと400() throws Exception {
            setAuth(memberTeamAId);
            long before = requestRepository.count();

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, slotAMorningId, OTHER_DATE, "PREFERRED"))))
                    .andExpect(status().isBadRequest());

            assertThat(requestRepository.count())
                    .as("クライアントの自己矛盾（越境ではない）なので 400。行は作られない")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("AC-8-06/AC-4群 存在しない slotId は403（存在オラクルを与えず404と畳まない）")
        void 存在しない枠IDは403() throws Exception {
            setAuth(memberTeamAId);
            long before = requestRepository.count();

            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    requestBody(scheduleAId, 999_999_999L, TARGET_DATE, "PREFERRED"))))
                    .andExpect(status().isForbidden());

            assertThat(requestRepository.count())
                    .as("他チームの枠と同じ応答にし、ID の存否を漏らさない")
                    .isEqualTo(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> requestBody(Long scheduleId, Long slotId, LocalDate slotDate, String preference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", scheduleId);
        body.put("slotId", slotId);
        body.put("slotDate", slotDate.toString());
        body.put("preference", preference);
        body.put("note", "A3 試練");
        return body;
    }

    private Long adminOfTeamA() {
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", "a3-request-admin-team-a@example.com")
                .getSingleResult()).longValue();
    }

    private Long insertCollectingSchedule(Long teamId, String title, Long createdBy) {
        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.of(2099, 1, 1, 0, 0))
                .createdBy(createdBy)
                .build());
        return schedule.getId();
    }

    private Long insertSlot(Long scheduleId, LocalDate slotDate, LocalTime start, LocalTime end) {
        ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(slotDate)
                .startTime(start)
                .endTime(end)
                .requiredCount(1)
                .build());
        return slot.getId();
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
                                + "VALUES (:email, 'A3', 'テスト', 'A3 テスト', 'ACTIVE', "
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
