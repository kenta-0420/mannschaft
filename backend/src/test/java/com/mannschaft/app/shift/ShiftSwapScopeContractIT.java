package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
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
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave6 — shift ドメイン（{@code ShiftSwapController} / {@code ShiftSwapService}）
 * 認可契約テスト。
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（Wave3-B6）。
 * {@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper} の構成をそのまま踏襲する。</p>
 *
 * <p>本テストが固定する受け入れ条件:</p>
 * <ol>
 *   <li>交代申請の一覧に<b>他チームの申請が混入しない</b>（テナント越境の封鎖）</li>
 *   <li>他チームの管理者が<b>交代申請を承認・却下できない</b>（BOLA の封鎖）</li>
 *   <li>取消・候補者選定は申請者本人または当該チーム ADMIN のみ</li>
 *   <li>上記を締めても<b>正当な利用者の正常系が壊れていない</b></li>
 * </ol>
 *
 * <p><b>象限</b>: 非ADMINメンバー（teamA の一般メンバー）/ 別 scope ADMIN（teamB の ADMIN が
 * teamA の swapId を直接指定）/ 正当 ADMIN（teamA の ADMIN）/ 正当メンバー。</p>
 *
 * <p>スコープ違反は同ドメインの既存規約に合わせ <b>403</b> で固定する
 *（{@code ShiftScheduleScopeContractIT} / {@code ShiftSlotScopeContractIT} と同一）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（シフト交代申請）認可契約テスト")
class ShiftSwapScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftSwapRequestRepository swapRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;    // TEAM A の ADMIN（正当）
    private Long adminTeamBId;    // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;   // TEAM A の非 ADMIN メンバー（申請者）
    private Long member2TeamAId;  // TEAM A のもう一人の非 ADMIN メンバー
    private Long outsiderId;      // どちらのチームにも属さない部外者

    private Long slotAId;         // TEAM A のシフト枠
    private Long slotBId;         // TEAM B のシフト枠

    private Long pendingSwapAId;  // TEAM A の PENDING 交代申請（申請者 = memberTeamA）
    private Long acceptedSwapAId; // TEAM A の ACCEPTED 交代申請（承認・却下の対象）
    private Long pendingSwapBId;  // TEAM B の PENDING 交代申請（一覧に混入してはならない）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6SWAP チームA");
        teamBId = insertTeam("WAVE6SWAP チームB");

        adminTeamAId = insertUser("wave6-swap-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-swap-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-swap-member-team-a@example.com");
        member2TeamAId = insertUser("wave6-swap-member2-team-a@example.com");
        outsiderId = insertUser("wave6-swap-outsider@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（ShiftScheduleScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, member2TeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        slotAId = insertSlot(insertSchedule(teamAId, "WAVE6SWAP A シフト"));
        slotBId = insertSlot(insertSchedule(teamBId, "WAVE6SWAP B シフト"));

        pendingSwapAId = insertSwap(slotAId, memberTeamAId, SwapRequestStatus.PENDING, false);
        pendingSwapBId = insertSwap(slotBId, adminTeamBId, SwapRequestStatus.PENDING, false);

        ShiftSwapRequestEntity accepted = buildSwap(slotAId, memberTeamAId, SwapRequestStatus.PENDING, false);
        accepted.accept(member2TeamAId);
        acceptedSwapAId = swapRepository.save(accepted).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/swap-requests?teamId=（一覧・★テナント越境の要）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/swap-requests?teamId=（一覧）")
    class ListSwapRequests {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/swap-requests").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAを指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/swap-requests").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/swap-requests").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(4));
        }

        @Test
        @DisplayName("★正当ADMINの一覧に他チーム（teamB）の交代申請が混入しない")
        void 他チームの交代申請が一覧に出ない() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/swap-requests").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk())
                    // teamB の申請 ID が結果に含まれないこと
                    .andExpect(jsonPath("$.data[?(@.id == " + pendingSwapBId + ")]").isEmpty())
                    // teamB のシフト枠 ID が結果に含まれないこと
                    .andExpect(jsonPath("$.data[?(@.slotId == " + slotBId + ")]").isEmpty());
        }

        @Test
        @DisplayName("★ステータス指定でも他チームの交代申請が混入しない")
        void ステータス指定でも他チームの交代申請が出ない() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/swap-requests")
                            .param("teamId", teamAId.toString())
                            .param("status", "PENDING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(pendingSwapAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /shifts/swap-requests（申請作成・slot 実体由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /shifts/swap-requests（申請作成）")
    class CreateSwapRequest {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(slotAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのslotIdを指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(slotAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201（正常系）")
        void 正当メンバーは201() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(slotAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody(Long slotId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slotId", slotId);
            body.put("reason", "体調不良のため");
            body.put("openCall", false);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /shifts/swap-requests/{swapId}/accept（承諾）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /shifts/swap-requests/{swapId}/accept（承諾）")
    class AcceptSwapRequest {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/accept", pendingSwapAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/accept", pendingSwapAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200（正常系）")
        void 正当メンバーは200() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/accept", pendingSwapAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /shifts/swap-requests/{swapId}/resolve（承認・却下・★BOLA の要）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /shifts/swap-requests/{swapId}/resolve（承認・却下）")
    class ResolveSwapRequest {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/resolve", acceptedSwapAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody("APPROVE"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("★別scope ADMIN（teamBのADMINがteamAのswapIdを直接指定）は承認できず403")
        void 別scopeADMINは承認できない() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/resolve", acceptedSwapAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody("APPROVE"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("★別scope ADMINは却下もできず403")
        void 別scopeADMINは却下もできない() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/resolve", acceptedSwapAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody("REJECT"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/resolve", acceptedSwapAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody("APPROVE"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/resolve", acceptedSwapAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody("APPROVE"))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> resolveBody(String action) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", action);
            body.put("adminNote", "契約テスト");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /shifts/swap-requests/{swapId}（取消）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /shifts/swap-requests/{swapId}（取消）")
    class CancelSwapRequest {

        @Test
        @DisplayName("同じチームでも申請者でない一般メンバーは403")
        void 申請者でない一般メンバーは403() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(delete("/api/v1/shifts/swap-requests/{id}", pendingSwapAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/swap-requests/{id}", pendingSwapAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("申請者本人は204（正常系）")
        void 申請者本人は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/swap-requests/{id}", pendingSwapAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("当該チームのADMINは204（正常系）")
        void 当該チームのADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/swap-requests/{id}", pendingSwapAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertSchedule(Long teamId, String title) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .build()).getId();
    }

    private Long insertSlot(Long scheduleId) {
        return slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .requiredCount(1)
                .build()).getId();
    }

    private ShiftSwapRequestEntity buildSwap(Long slotId, Long requesterId,
                                             SwapRequestStatus status, boolean openCall) {
        return ShiftSwapRequestEntity.builder()
                .slotId(slotId)
                .requesterId(requesterId)
                .status(status)
                .reason("契約テスト用")
                .isOpenCall(openCall)
                .recipientMode(openCall ? "OPEN_CALL" : "SPECIFIC")
                .build();
    }

    private Long insertSwap(Long slotId, Long requesterId, SwapRequestStatus status, boolean openCall) {
        return swapRepository.save(buildSwap(slotId, requesterId, status, openCall)).getId();
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
                                + "VALUES (:email, 'WAVE6SWAP', 'テスト', 'WAVE6SWAP テスト', 'ACTIVE', "
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
