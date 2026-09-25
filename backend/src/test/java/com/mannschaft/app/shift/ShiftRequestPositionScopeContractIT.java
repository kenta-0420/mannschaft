package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * shift ドメイン（{@code ShiftRequestController} / {@code ShiftPositionController}）認可契約テスト。
 *
 * <p>認可根治 Wave6 で per-scope 認可を固定し、CMP-260923-0954 W1 で<b>存在オラクル</b>を解消した。
 * 個別 ID（パスの requestId / positionId、クエリ・本文の scheduleId）を受け取る EP は、
 * 越境（当該チームに所属しない利用者）への応答を<b>不在 ID への応答と完全に一致</b>させる
 * （status・error.code・error.message）。同一チーム内の権限不足は従来どおり 403。
 * {@code ?teamId=} 一覧型は非メンバーに常に同一の 403 のまま（01_authorization_baseline.md §3.3.1）。</p>
 *
 * <h2>EP 別許可主体表（本クラスが固定する契約）</h2>
 * <pre>
 * EP                               | 許可                                         | 同チーム権限不足 | 越境・不在・親削除済み
 * GET  /shifts/requests?scheduleId | SYSTEM_ADMIN / ADMIN・DEPUTY_ADMIN            | 403 COMMON_002   | 404 SHIFT_001
 * GET  /shifts/requests/summary    | 同上                                          | 403 COMMON_002   | 404 SHIFT_001
 * POST /shifts/requests            | SYSTEM_ADMIN / 在籍メンバー（SUPPORTER 除く） | 403 COMMON_002 ※ | 404 SHIFT_001
 * PATCH・DELETE /shifts/requests/id | SYSTEM_ADMIN / 提出者本人 / ADMIN・DEPUTY     | 403 COMMON_002   | 404 SHIFT_003
 * PATCH・DELETE /shifts/positions/id| SYSTEM_ADMIN / ADMIN・DEPUTY_ADMIN            | 403 COMMON_002   | 404 SHIFT_004
 * GET・POST /shifts/positions?teamId| 変更なし                                      | 403 COMMON_002   | 常に同一の 403 COMMON_002
 * ※ user_roles のみの ADMIN（在籍なし）は提出を許可しないが 403（404 に化けない）
 * </pre>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（シフト希望・ポジション）認可契約テスト")
class ShiftRequestPositionScopeContractIT extends AbstractMySqlIntegrationTest {

    /** どのテーブルにも存在しない ID（採番が届かない値）。 */
    private static final long MISSING_ID = 987_654_321L;

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
    private Long supporterTeamAId;
    private Long userRolesOnlyAdminAId;
    private Long userRolesOnlyDeputyAId;
    private Long systemAdminId;
    private Long outsiderId;

    private Long scheduleAId;        // TEAM A の COLLECTING スケジュール
    private Long requestAId;         // TEAM A のシフト希望（提出者 = memberTeamA）
    private Long positionAId;        // TEAM A のポジション
    private Long deletedScheduleId;  // TEAM A の論理削除済みスケジュール
    private Long orphanRequestId;    // 論理削除済みスケジュールに属する希望（提出者 = memberTeamA）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6REQ チームA");
        teamBId = insertTeam("WAVE6REQ チームB");

        adminTeamAId = insertUser("wave6-req-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-req-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-req-member-team-a@example.com");
        member2TeamAId = insertUser("wave6-req-member2-team-a@example.com");
        supporterTeamAId = insertUser("wave6-req-supporter-team-a@example.com");
        userRolesOnlyAdminAId = insertUser("wave6-req-ur-admin-team-a@example.com");
        userRolesOnlyDeputyAId = insertUser("wave6-req-ur-deputy-team-a@example.com");
        systemAdminId = insertUser("wave6-req-system-admin@example.com");
        outsiderId = insertUser("wave6-req-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, member2TeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterTeamAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        // user_roles のみの管理者（memberships 行なし）
        MembershipTestHelper.insertUserRole(em, userRolesOnlyAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, userRolesOnlyDeputyAId, "DEPUTY_ADMIN", teamAId, null);
        // SYSTEM_ADMIN はプラットフォーム級。当該チームの memberships は張らない。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        scheduleAId = saveCollectingSchedule(teamAId, "WAVE6REQ 希望収集中シフト");

        requestAId = saveRequest(scheduleAId, memberTeamAId, LocalDate.of(2026, 3, 2));

        positionAId = positionRepository.save(ShiftPositionEntity.builder()
                .teamId(teamAId)
                .name("キッチン")
                .displayOrder(1)
                .isActive(true)
                .build()).getId();

        ShiftScheduleEntity deleted = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE6REQ 削除済みシフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(30))
                .createdBy(adminTeamAId)
                .build());
        deletedScheduleId = deleted.getId();
        orphanRequestId = saveRequest(deletedScheduleId, memberTeamAId, LocalDate.of(2026, 4, 2));
        deleted.softDelete();
        scheduleRepository.save(deleted);

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
        @DisplayName("AC-2: 非ADMINメンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(memberTeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()),
                        403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_001）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(
                        get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()),
                        get("/api/v1/shifts/requests").param("scheduleId", String.valueOf(MISSING_ID)),
                        404, "SHIFT_001");
            }
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("AC-3: user_rolesのみのADMIN・DEPUTY_ADMINは200")
        void userRolesOnly管理者は200() throws Exception {
            for (Long actor : List.of(userRolesOnlyAdminAId, userRolesOnlyDeputyAId)) {
                setAuth(actor);
                mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("AC-5: 非メンバーのSYSTEM_ADMINは200、親が論理削除済みなら不在と同一の404")
        void SYSTEM_ADMIN() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk());
            assertSameAsMissing(
                    get("/api/v1/shifts/requests").param("scheduleId", deletedScheduleId.toString()),
                    get("/api/v1/shifts/requests").param("scheduleId", String.valueOf(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("AC-5: 親が論理削除済みの一覧は、越境者にも不在と同一の404")
        void 親削除済みは越境者にも不在と同一() throws Exception {
            setAuth(adminTeamBId);
            assertSameAsMissing(
                    get("/api/v1/shifts/requests").param("scheduleId", deletedScheduleId.toString()),
                    get("/api/v1/shifts/requests").param("scheduleId", String.valueOf(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("AC-9: scheduleId 省略・非数値は400、0・負数・Long.MAXは404 SHIFT_001（500にならない）")
        void ID境界() throws Exception {
            for (Long actor : List.of(adminTeamAId, outsiderId)) {
                setAuth(actor);
                mockMvc.perform(get("/api/v1/shifts/requests")).andExpect(status().isBadRequest());
                mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", "abc"))
                        .andExpect(status().isBadRequest());
                for (String id : List.of("0", "-1", String.valueOf(Long.MAX_VALUE))) {
                    expectError(get("/api/v1/shifts/requests").param("scheduleId", id), 404, "SHIFT_001");
                }
            }
        }

        @Test
        @DisplayName("AC-12: 0件・1件・複数件で発行SQL数が一定（認可がN+1にならない）")
        void SQL回数が件数に依存しない() throws Exception {
            Long empty = saveCollectingSchedule(teamAId, "WAVE6REQ 0件");
            Long many = saveCollectingSchedule(teamAId, "WAVE6REQ 3件");
            saveRequest(many, memberTeamAId, LocalDate.of(2026, 3, 2));
            saveRequest(many, member2TeamAId, LocalDate.of(2026, 3, 3));
            saveRequest(many, adminTeamAId, LocalDate.of(2026, 3, 4));
            em.flush();
            em.clear();

            setAuth(adminTeamAId);
            // 暖機（初回のみのキャッシュ充填を計測から外す）
            mockMvc.perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()));

            long c0 = countStatements(get("/api/v1/shifts/requests").param("scheduleId", empty.toString()), 0);
            long c1 = countStatements(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()), 1);
            long c3 = countStatements(get("/api/v1/shifts/requests").param("scheduleId", many.toString()), 3);
            assertThat(c1).as("1件").isEqualTo(c0);
            assertThat(c3).as("3件").isEqualTo(c0);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /shifts/requests/summary?scheduleId=（提出サマリー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /shifts/requests/summary?scheduleId=（提出サマリー）")
    class GetRequestSummary {

        @Test
        @DisplayName("AC-2: 非ADMINメンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(memberTeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()),
                        403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_001）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(
                        get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()),
                        get("/api/v1/shifts/requests/summary").param("scheduleId", String.valueOf(MISSING_ID)),
                        404, "SHIFT_001");
            }
        }

        @Test
        @DisplayName("正当ADMIN・user_rolesのみのADMIN・SYSTEM_ADMINは200")
        void 許可主体は200() throws Exception {
            for (Long actor : List.of(adminTeamAId, userRolesOnlyAdminAId, systemAdminId)) {
                setAuth(actor);
                mockMvc.perform(get("/api/v1/shifts/requests/summary").param("scheduleId", scheduleAId.toString()))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("AC-5: SYSTEM_ADMINでも親が論理削除済みなら不在と同一の404")
        void 親削除済みは404() throws Exception {
            setAuth(systemAdminId);
            assertSameAsMissing(
                    get("/api/v1/shifts/requests/summary").param("scheduleId", deletedScheduleId.toString()),
                    get("/api/v1/shifts/requests/summary").param("scheduleId", String.valueOf(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("AC-9: scheduleId 省略は400、0・負数・Long.MAXは404 SHIFT_001")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/requests/summary")).andExpect(status().isBadRequest());
            for (String id : List.of("0", "-1", String.valueOf(Long.MAX_VALUE))) {
                expectError(get("/api/v1/shifts/requests/summary").param("scheduleId", id), 404, "SHIFT_001");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /shifts/requests（希望提出・本文の scheduleId）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /shifts/requests（希望提出）")
    class SubmitRequest {

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在scheduleIdと不在scheduleIdで応答が一致（404 SHIFT_001）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(submit(submitBody(scheduleAId)), submit(submitBody(MISSING_ID)),
                        404, "SHIFT_001");
            }
        }

        @Test
        @DisplayName("AC-11: 越境で404にした提出はDBを一切変えない")
        void 越境はDB不変() throws Exception {
            long before = countRequests();
            setAuth(adminTeamBId);
            mockMvc.perform(submit(submitBody(scheduleAId))).andExpect(status().isNotFound());
            em.flush();
            em.clear();
            assertThat(countRequests()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-2: SUPPORTERは403 COMMON_002")
        void SUPPORTERは403() throws Exception {
            setAuth(supporterTeamAId);
            expectError(submit(submitBody(scheduleAId)), 403, "COMMON_002");
        }

        @Test
        @DisplayName("AC-3: user_rolesのみのADMIN（在籍なし）は提出不可だが403のまま（404に化けない）")
        void userRolesOnlyADMINは403() throws Exception {
            setAuth(userRolesOnlyAdminAId);
            expectError(submit(submitBody(scheduleAId)), 403, "COMMON_002");
        }

        @Test
        @DisplayName("正当メンバーは201（正常系）")
        void 正当メンバーは201() throws Exception {
            setAuth(member2TeamAId);
            mockMvc.perform(submit(submitBody(scheduleAId))).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("AC-5: 非メンバーのSYSTEM_ADMINは201、親が論理削除済みなら不在と同一の404")
        void SYSTEM_ADMIN() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(submit(submitBody(scheduleAId))).andExpect(status().isCreated());
            assertSameAsMissing(submit(submitBody(deletedScheduleId)), submit(submitBody(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("AC-9: 本文scheduleIdがnullなら400、0・負数・Long.MAXは404 SHIFT_001")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(submit(submitBody(null))).andExpect(status().isBadRequest());
            for (Long id : List.of(0L, -1L, Long.MAX_VALUE)) {
                expectError(submit(submitBody(id)), 404, "SHIFT_001");
            }
        }

        private Map<String, Object> submitBody(Long scheduleId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scheduleId", scheduleId);
            body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
            body.put("preference", "PREFERRED");
            body.put("note", "契約テスト");
            return body;
        }

        private RequestBuilder submit(Map<String, Object> body) throws Exception {
            return post("/api/v1/shifts/requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH /shifts/requests/{requestId}（希望更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH /shifts/requests/{requestId}（希望更新）")
    class UpdateRequest {

        @Test
        @DisplayName("AC-2: 同じチームでも提出者でない一般メンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(member2TeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(patchRequest(requestAId), 403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_003）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(patchRequest(requestAId), patchRequest(MISSING_ID), 404, "SHIFT_003");
            }
        }

        @Test
        @DisplayName("AC-5: 親スケジュールが論理削除済みの希望は、本人・ADMIN・SYSTEM_ADMIN・越境者のいずれにも不在と同一の404")
        void 親削除済みは不在と同一() throws Exception {
            for (Long actor : List.of(memberTeamAId, adminTeamAId, systemAdminId, adminTeamBId)) {
                setAuth(actor);
                assertSameAsMissing(patchRequest(orphanRequestId), patchRequest(MISSING_ID), 404, "SHIFT_003");
            }
        }

        @Test
        @DisplayName("AC-11: 越境で404にした更新はDBを一切変えない")
        void 越境はDB不変() throws Exception {
            Object[] before = requestRow(requestAId);
            setAuth(adminTeamBId);
            mockMvc.perform(patchRequest(requestAId)).andExpect(status().isNotFound());
            em.flush();
            em.clear();
            assertThat(requestRow(requestAId)).containsExactly(before);
        }

        @Test
        @DisplayName("提出者本人・当該チームADMIN・user_rolesのみのADMIN・SYSTEM_ADMINは200")
        void 許可主体は200() throws Exception {
            for (Long actor : List.of(memberTeamAId, adminTeamAId, userRolesOnlyAdminAId, systemAdminId)) {
                setAuth(actor);
                mockMvc.perform(patchRequest(requestAId)).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("AC-9: 0・負数・Long.MAXは404 SHIFT_003、非数値は400")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            for (Long id : List.of(0L, -1L, Long.MAX_VALUE)) {
                expectError(patchRequest(id), 404, "SHIFT_003");
            }
            mockMvc.perform(patch("/api/v1/shifts/requests/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isBadRequest());
        }

        private RequestBuilder patchRequest(Long id) throws Exception {
            return patch("/api/v1/shifts/requests/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateBody()));
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
        @DisplayName("AC-2: 同じチームでも提出者でない一般メンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(member2TeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(delete("/api/v1/shifts/requests/{id}", requestAId), 403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_003）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(delete("/api/v1/shifts/requests/{id}", requestAId),
                        delete("/api/v1/shifts/requests/{id}", MISSING_ID), 404, "SHIFT_003");
            }
        }

        @Test
        @DisplayName("AC-11: 越境で404にした削除は行を消さない")
        void 越境はDB不変() throws Exception {
            long before = countRequests();
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId)).andExpect(status().isNotFound());
            em.flush();
            em.clear();
            assertThat(countRequests()).isEqualTo(before);
            assertThat(requestRepository.findById(requestAId)).isPresent();
        }

        @Test
        @DisplayName("AC-5: 親が論理削除済みなら本人・SYSTEM_ADMINでも不在と同一の404（行は残る）")
        void 親削除済みは404() throws Exception {
            for (Long actor : List.of(memberTeamAId, systemAdminId)) {
                setAuth(actor);
                assertSameAsMissing(delete("/api/v1/shifts/requests/{id}", orphanRequestId),
                        delete("/api/v1/shifts/requests/{id}", MISSING_ID), 404, "SHIFT_003");
            }
            assertThat(requestRepository.findById(orphanRequestId)).isPresent();
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

        @Test
        @DisplayName("AC-3: user_rolesのみのDEPUTY_ADMINは204")
        void userRolesOnlyDEPUTYは204() throws Exception {
            setAuth(userRolesOnlyDeputyAId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-5: 非メンバーのSYSTEM_ADMINは204")
        void SYSTEM_ADMINは204() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", requestAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-9: 0・負数・Long.MAXは404 SHIFT_003、非数値は400")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            for (Long id : List.of(0L, -1L, Long.MAX_VALUE)) {
                expectError(delete("/api/v1/shifts/requests/{id}", id), 404, "SHIFT_003");
            }
            mockMvc.perform(delete("/api/v1/shifts/requests/abc")).andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /shifts/positions?teamId=（ポジション一覧・AC-6 変更なし）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /shifts/positions?teamId=（一覧）")
    class ListPositions {

        @Test
        @DisplayName("AC-6: 非メンバーには実在teamIdでも不在teamIdでも常に同一の403 COMMON_002")
        void 非メンバーは常に同一の403() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(
                        get("/api/v1/shifts/positions").param("teamId", teamAId.toString()),
                        get("/api/v1/shifts/positions").param("teamId", String.valueOf(MISSING_ID)),
                        403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("正当メンバーは200（正常系）")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("AC-12: 0件・1件・複数件で発行SQL数が一定")
        void SQL回数が件数に依存しない() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()));
            long c1 = countStatements(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()), 1);

            savePosition(teamAId, "ホール", 2);
            savePosition(teamAId, "レジ", 3);
            em.flush();
            em.clear();
            long c3 = countStatements(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()), 3);

            em.createNativeQuery("DELETE FROM shift_positions WHERE team_id = :t")
                    .setParameter("t", teamAId).executeUpdate();
            em.flush();
            em.clear();
            long c0 = countStatements(get("/api/v1/shifts/positions").param("teamId", teamAId.toString()), 0);

            assertThat(c1).as("1件").isEqualTo(c0);
            assertThat(c3).as("3件").isEqualTo(c0);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST /shifts/positions?teamId=（ポジション作成・AC-6 変更なし）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /shifts/positions?teamId=（作成）")
    class CreatePosition {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            expectError(createPosition(teamAId), 403, "COMMON_002");
        }

        @Test
        @DisplayName("AC-6: 別チームADMINには実在teamIdでも不在teamIdでも同一の403 COMMON_002")
        void 別scopeADMINは常に同一の403() throws Exception {
            setAuth(adminTeamBId);
            assertSameAsMissing(createPosition(teamAId), createPosition(MISSING_ID), 403, "COMMON_002");
        }

        @Test
        @DisplayName("正当ADMINは201（正常系）")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(createPosition(teamAId)).andExpect(status().isCreated());
        }

        private RequestBuilder createPosition(Long teamId) throws Exception {
            return post("/api/v1/shifts/positions").param("teamId", teamId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "ホール", "displayOrder", 2)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PATCH /shifts/positions/{positionId}（更新・実体由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PATCH /shifts/positions/{positionId}（更新）")
    class UpdatePosition {

        @Test
        @DisplayName("AC-2: 非ADMINメンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(memberTeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(patchPosition(positionAId), 403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_004）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(patchPosition(positionAId), patchPosition(MISSING_ID), 404, "SHIFT_004");
            }
        }

        @Test
        @DisplayName("AC-11: 越境で404にした更新はDBを一切変えない（name・updated_at。version 列は持たない）")
        void 越境はDB不変() throws Exception {
            Object[] before = positionRow(positionAId);
            setAuth(adminTeamBId);
            mockMvc.perform(patchPosition(positionAId)).andExpect(status().isNotFound());
            em.flush();
            em.clear();
            assertThat(positionRow(positionAId)).containsExactly(before);
        }

        @Test
        @DisplayName("正当ADMIN・user_rolesのみのADMIN・SYSTEM_ADMINは200")
        void 許可主体は200() throws Exception {
            for (Long actor : List.of(adminTeamAId, userRolesOnlyAdminAId, systemAdminId)) {
                setAuth(actor);
                mockMvc.perform(patchPosition(positionAId)).andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("AC-9: 0・負数・Long.MAXは404 SHIFT_004、非数値は400")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            for (Long id : List.of(0L, -1L, Long.MAX_VALUE)) {
                expectError(patchPosition(id), 404, "SHIFT_004");
            }
            mockMvc.perform(patch("/api/v1/shifts/positions/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名"))))
                    .andExpect(status().isBadRequest());
        }

        private RequestBuilder patchPosition(Long id) throws Exception {
            return patch("/api/v1/shifts/positions/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "改名")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. DELETE /shifts/positions/{positionId}（削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. DELETE /shifts/positions/{positionId}（削除）")
    class DeletePosition {

        @Test
        @DisplayName("AC-2: 非ADMINメンバー・SUPPORTERは403 COMMON_002")
        void 同チーム権限不足は403() throws Exception {
            for (Long actor : List.of(memberTeamAId, supporterTeamAId)) {
                setAuth(actor);
                expectError(delete("/api/v1/shifts/positions/{id}", positionAId), 403, "COMMON_002");
            }
        }

        @Test
        @DisplayName("AC-1: 別チームADMIN・部外者は、実在IDと不在IDで応答が一致（404 SHIFT_004）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(delete("/api/v1/shifts/positions/{id}", positionAId),
                        delete("/api/v1/shifts/positions/{id}", MISSING_ID), 404, "SHIFT_004");
            }
        }

        @Test
        @DisplayName("AC-11: 越境で404にした削除は行を消さない")
        void 越境はDB不変() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId)).andExpect(status().isNotFound());
            em.flush();
            em.clear();
            assertThat(positionRepository.findById(positionAId)).isPresent();
        }

        @Test
        @DisplayName("正当ADMINは204（正常系）")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-3/AC-5: user_rolesのみのDEPUTY_ADMIN・非メンバーのSYSTEM_ADMINは204")
        void userRolesOnlyとSYSTEM_ADMINは204() throws Exception {
            setAuth(userRolesOnlyDeputyAId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", positionAId))
                    .andExpect(status().isNoContent());
            Long another = savePosition(teamAId, "別ポジション", 9);
            em.flush();
            em.clear();
            setAuth(systemAdminId);
            mockMvc.perform(delete("/api/v1/shifts/positions/{id}", another))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-9: 0・負数・Long.MAXは404 SHIFT_004、非数値は400")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            for (Long id : List.of(0L, -1L, Long.MAX_VALUE)) {
                expectError(delete("/api/v1/shifts/positions/{id}", id), 404, "SHIFT_004");
            }
            mockMvc.perform(delete("/api/v1/shifts/positions/abc")).andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET /shifts/my/requests（自分の希望一覧・構造的に自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET /shifts/my/requests（自分の希望一覧）")
    class ListMyRequests {

        @Test
        @DisplayName("提出者本人には自分の希望が返る（正常系・親削除済みも含む）")
        void 提出者本人には自分の希望が返る() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
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

    /**
     * 実在リソースへの応答と不在 ID への応答が status・error.code・error.message まで一致することを検証する。
     * 一致だけでは両方壊れたとき偽 green になるため、期待する絶対値（status・code）も併せて固定する。
     */
    private void assertSameAsMissing(RequestBuilder real, RequestBuilder missing,
                                     int expectedStatus, String expectedCode) throws Exception {
        ErrorView realView = perform(real);
        ErrorView missingView = perform(missing);
        assertThat(realView).as("実在IDへの応答と不在IDへの応答が一致すること").isEqualTo(missingView);
        assertThat(realView.status()).isEqualTo(expectedStatus);
        assertThat(realView.code()).isEqualTo(expectedCode);
        assertThat(realView.message()).isNotBlank();
    }

    private void expectError(RequestBuilder request, int expectedStatus, String expectedCode) throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).isEqualTo(expectedStatus);
        assertThat(view.code()).isEqualTo(expectedCode);
    }

    private ErrorView perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode error = body.isBlank() ? null : objectMapper.readTree(body).path("error");
        return new ErrorView(result.getResponse().getStatus(),
                error == null ? null : error.path("code").asText(null),
                error == null ? null : error.path("message").asText(null));
    }

    private record ErrorView(int status, String code, String message) {
    }

    private long countStatements(RequestBuilder request, int expectedSize) throws Exception {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(expectedSize));
        long count = stats.getPrepareStatementCount();
        // 失敗時の切り分け用に内訳を残す（件数に比例して増えるのが SQL のどの種類かを見る）
        System.out.printf("[AC-12] size=%d prepared=%d entityLoad=%d queryExec=%d flush=%d update=%d fetch=%d%n",
                expectedSize, count, stats.getEntityLoadCount(), stats.getQueryExecutionCount(),
                stats.getFlushCount(), stats.getEntityUpdateCount(), stats.getEntityFetchCount());
        em.clear();
        return count;
    }

    private long countRequests() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM shift_requests").getSingleResult()).longValue();
    }

    private Object[] requestRow(Long id) {
        return (Object[]) em.createNativeQuery(
                        "SELECT preference, note, updated_at FROM shift_requests WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private Object[] positionRow(Long id) {
        return (Object[]) em.createNativeQuery(
                        "SELECT name, display_order, is_active, updated_at FROM shift_positions WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private Long saveCollectingSchedule(Long teamId, String title) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(30))
                .createdBy(adminTeamAId)
                .build()).getId();
    }

    private Long saveRequest(Long scheduleId, Long userId, LocalDate date) {
        return requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleId)
                .userId(userId)
                .slotDate(date)
                .preference(ShiftPreference.PREFERRED)
                .note("希望します")
                .build()).getId();
    }

    private Long savePosition(Long teamId, String name, int order) {
        return positionRepository.save(ShiftPositionEntity.builder()
                .teamId(teamId)
                .name(name)
                .displayOrder(order)
                .isActive(true)
                .build()).getId();
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
