package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave6 — shift ドメイン（{@code ShiftRequestController} / {@code ShiftPositionController}）
 * 認可契約テスト。
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（Wave3-B6）。スコープ違反は同ドメインの
 * 既存規約に合わせ <b>403</b> で固定する。</p>
 *
 * <p><b>象限</b>: 非ADMINメンバー / 別 scope ADMIN（BOLA）/ 正当 ADMIN / 正当な本人。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（シフト希望・ポジション）認可契約テスト")
class ShiftRequestPositionScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftRequestRepository requestRepository;

    @Autowired
    private ShiftPositionRepository positionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;
    private Long adminTeamBId;
    private Long memberTeamAId;
    private Long member2TeamAId;
    private Long outsiderId;

    private Long scheduleAId;   // TEAM A の COLLECTING スケジュール
    private Long requestAId;    // TEAM A のシフト希望（提出者 = memberTeamA）
    private Long positionAId;   // TEAM A のポジション

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6REQ チームA");
        teamBId = insertTeam("WAVE6REQ チームB");

        adminTeamAId = insertUser("wave6-req-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-req-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-req-member-team-a@example.com");
        member2TeamAId = insertUser("wave6-req-member2-team-a@example.com");
        outsiderId = insertUser("wave6-req-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, member2TeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        scheduleAId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE6REQ 希望収集中シフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(30))
                .createdBy(adminTeamAId)
                .build()).getId();

        requestAId = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleAId)
                .userId(memberTeamAId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .preference(ShiftPreference.PREFERRED)
                .note("希望します")
                .build()).getId();

        positionAId = positionRepository.save(ShiftPositionEntity.builder()
                .teamId(teamAId)
                .name("キッチン")
                .displayOrder(1)
                .isActive(true)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/requests?scheduleId=（他メンバー分を含む一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/requests?scheduleId=（希望一覧）")
    class ListRequests {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのscheduleIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /shifts/requests/summary?scheduleId=（提出サマリー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /shifts/requests/summary?scheduleId=（提出サマリー）")
    class GetRequestSummary {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /shifts/requests（希望提出）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /shifts/requests（希望提出）")
    class SubmitRequest {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(submitBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(submitBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201（正常系）")
        void 正当メンバーは201() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(submitBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> submitBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scheduleId", scheduleAId);
            body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
            body.put("preference", "PREFERRED");
            body.put("note", "契約テスト");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH /shifts/requests/{requestId}（希望更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH /shifts/requests/{requestId}（希望更新）")
    class UpdateRequest {

        @Test
        @DisplayName("同じチームでも提出者でない一般メンバーは403")
        void 提出者でない一般メンバーは403() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(patch("/api/v1/shifts/requests/{id}", requestAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/requests/{id}", requestAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("提出者本人は200（正常系）")
        void 提出者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/requests/{id}", requestAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("preference", "AVAILABLE");
            body.put("note", "更新後");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /shifts/requests/{requestId}（希望削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /shifts/requests/{requestId}（希望削除）")
    class DeleteRequest {

        @Test
        @DisplayName("同じチームでも提出者でない一般メンバーは403")
        void 提出者でない一般メンバーは403() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("提出者本人は204（正常系）")
        void 提出者本人は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("当該チームのADMINは204（正常系）")
        void 当該チームのADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /shifts/positions?teamId=（ポジション一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /shifts/positions?teamId=（一覧）")
    class ListPositions {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAを指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200（正常系）")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST /shifts/positions?teamId=（ポジション作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /shifts/positions?teamId=（作成）")
    class CreatePosition {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/positions").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "ホール", "displayOrder", 2))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/positions").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "ホール", "displayOrder", 2))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201（正常系）")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/positions").param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "ホール", "displayOrder", 2))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PATCH / DELETE /shifts/positions/{positionId}（更新・削除・実体由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PATCH /shifts/positions/{positionId}（更新）")
    class UpdatePosition {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/positions/{id}", positionAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがpositionIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/positions/{id}", positionAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/positions/{id}", positionAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名"))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("9. DELETE /shifts/positions/{positionId}（削除）")
    class DeletePosition {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204（正常系）")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET /shifts/my/requests（自分の希望一覧・構造的に自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET /shifts/my/requests（自分の希望一覧）")
    class ListMyRequests {

        @Test
        @DisplayName("提出者本人には自分の希望が返る（正常系）")
        void 提出者本人には自分の希望が返る() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("★他人には他人の希望が返らない（リポジトリ引きが userId 複合のため構造的に自己スコープ）")
        void 他人には他人の希望が返らない() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

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
                                + "VALUES (:email, 'WAVE6REQ', 'テスト', 'WAVE6REQ テスト', 'ACTIVE', "
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
