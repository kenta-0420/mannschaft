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

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — shift ドメイン（{@code ShiftChangeRequestController} 参照系）認可契約テスト（試練）。
 *
 * <p>封鎖する 2 つの実穴:</p>
 * <ol>
 *   <li><b>権限昇格</b>: 一覧 API が {@code @RequestParam String role} を認可の判断材料に
 *       していたため、<b>一般メンバーが自己申告するだけで</b>スケジュール全件を取得できた。
 *       本改修で {@code role} を撤廃し、サーバー側のロール判定に置き換えた。
 *       撤廃後もクエリに残骸が付いて送られる可能性があるため、
 *       「{@code ?role=ADMIN} を付けても昇格しない」ことを明示的に検証する。</li>
 *   <li><b>死文だった IDOR チェック</b>: 詳細 API は Javadoc に「IDOR チェック付き」と
 *       書かれながら本体に照合コードが無く、任意 ID の依頼を閲覧できた。</li>
 * </ol>
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（Wave3-B6・同ドメイン）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（変更依頼・参照系）認可契約テスト（試練）")
class ShiftChangeRequestScopeContractIT extends AbstractMySqlIntegrationTest {

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

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;    // TEAM A の ADMIN（全件見える）
    private Long adminTeamBId;    // TEAM B の ADMIN（越境攻撃者）
    private Long memberTeamAId;   // TEAM A の一般メンバー（自分の分のみ）
    private Long otherMemberId;   // TEAM A の別メンバー（他人の依頼の持ち主）
    private Long outsiderId;      // 非メンバー
    private Long userRolesOnlyAdminAId; // TEAM A の user_roles のみ ADMIN（memberships 無し）
    private Long systemAdminId;   // SYSTEM_ADMIN（非メンバー）

    private Long scheduleAId;
    private Long myRequestId;     // memberTeamA の依頼
    private Long othersRequestId; // otherMember の依頼

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6 変更依頼 チームA");
        teamBId = insertTeam("WAVE6 変更依頼 チームB");

        adminTeamAId = insertUser("wave6-cr-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-cr-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-cr-member-team-a@example.com");
        otherMemberId = insertUser("wave6-cr-other-member@example.com");
        outsiderId = insertUser("wave6-cr-outsider@example.com");
        userRolesOnlyAdminAId = insertUser("wave6-cr-ur-admin@example.com");
        systemAdminId = insertUser("wave6-cr-sysadmin@example.com");
        MembershipTestHelper.insertUserRole(em, userRolesOnlyAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        ShiftScheduleEntity scheduleA = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE6 変更依頼テスト用スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(adminTeamAId)
                .build());
        scheduleAId = scheduleA.getId();

        myRequestId = changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleAId)
                .requestType(ChangeRequestType.OPEN_CALL)
                .requestedBy(memberTeamAId)
                .reason("自分の依頼")
                .build()).getId();

        othersRequestId = changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleAId)
                .requestType(ChangeRequestType.OPEN_CALL)
                .requestedBy(otherMemberId)
                .reason("他人の依頼")
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/change-requests?scheduleId=（一覧・★権限昇格の本丸★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/change-requests?scheduleId=（一覧）")
    class ListChangeRequests {

        @Test
        @DisplayName("一般メンバーは自分の依頼のみ（他人の依頼は返らない）")
        void 一般メンバーは自分の依頼のみ() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(myRequestId));
        }

        /**
         * 本戦役の本丸。旧実装では {@code ?role=ADMIN} を付けるだけで全件が返っていた。
         * {@code role} 撤廃後は未知のクエリパラメータとして無視され、昇格しないこと。
         */
        @Test
        @DisplayName("一般メンバーが role=ADMIN を付けても全件は返らない（権限昇格の封鎖）")
        void 一般メンバーがroleADMINを付けても昇格しない() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString())
                            .param("role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(myRequestId));
        }

        @Test
        @DisplayName("正当ADMINは全件（2件）")
        void 正当ADMINは全件() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("非メンバーは404（存在オラクル是正 CMP-260923-0954 W2・不在scheduleIdと同一応答）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"));
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は404（BOLA・存在オラクル是正 W2）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"));
        }

        @Test
        @DisplayName("別scope ADMIN が role=ADMIN を付けても404（BOLA・権限昇格の複合）")
        void 別scopeADMINがroleADMINを付けても404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString())
                            .param("role", "ADMIN"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"));
        }

        /**
         * AC-1（存在オラクル是正 W2）: 別scope ADMIN が実在する他チームの scheduleId を叩いた応答と、
         * 不在の scheduleId を叩いた応答が status・error.code で一致すること。
         */
        @Test
        @DisplayName("★AC-1: 別scope ADMINの他チームscheduleIdと不在scheduleIdは同一応答")
        void AC1_別scopeADMINの他チームscheduleIdと不在scheduleIdは同一応答() throws Exception {
            setAuth(adminTeamBId);
            var crossTeam = mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"))
                    .andReturn();
            var absent = mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", "999999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(crossTeam.getResponse().getStatus())
                    .isEqualTo(absent.getResponse().getStatus());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /shifts/change-requests/{id}（詳細・★死文だったIDORチェック★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /shifts/change-requests/{id}（詳細）")
    class GetChangeRequest {

        @Test
        @DisplayName("依頼者本人は200")
        void 依頼者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(myRequestId));
        }

        @Test
        @DisplayName("当該チームADMINは他人の依頼も200")
        void 当該チームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", othersRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("同じチームの他メンバーは404（存在秘匿）")
        void 同チームの他メンバーは404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", othersRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINは404（越境・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは404（越境・存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PATCH /shifts/change-requests/{id}/review（審査・★存在オラクル是正 W2★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH /shifts/change-requests/{id}/review（審査）")
    class ReviewChangeRequest {

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在オラクル是正 W2）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"));
        }

        @Test
        @DisplayName("★AC-1: 別scope ADMINの他チーム依頼idと不在idは同一応答")
        void AC1_別scopeADMINの他チーム依頼idと不在idは同一応答() throws Exception {
            setAuth(adminTeamBId);
            var crossTeam = mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"))
                    .andReturn();
            var absent = mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reviewBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(crossTeam.getResponse().getStatus())
                    .isEqualTo(absent.getResponse().getStatus());
        }

        private Map<String, Object> reviewBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("decision", "ACCEPTED");
            body.put("reviewComment", "契約テスト");
            body.put("version", 0);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. DELETE /shifts/change-requests/{id}（取下げ・★存在オラクル是正 W2★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE /shifts/change-requests/{id}（取下げ）")
    class WithdrawChangeRequest {

        @Test
        @DisplayName("依頼者本人は204（正常系）")
        void 依頼者本人は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("同じチームの他メンバーは403（SHIFT_019・存在秘匿しない）")
        void 同チームの他メンバーは403() throws Exception {
            setAuth(otherMemberId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_019"));
        }

        @Test
        @DisplayName("当該チームADMINも本人でなければ403（代理取下げは対象外）")
        void 当該チームADMINも本人でなければ403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_019"));
        }

        @Test
        @DisplayName("SYSTEM_ADMINも本人でなければ403（SHIFT_019）で、依頼の status・version は変わらない")
        void SYSTEM_ADMINも本人でなければ403でDB不変() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_019"));
            em.flush();
            em.clear();
            ShiftChangeRequestEntity after = changeRequestRepository.findById(myRequestId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(after.getStatus())
                    .isEqualTo(com.mannschaft.app.shift.ChangeRequestStatus.OPEN);
            org.assertj.core.api.Assertions.assertThat(after.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("別scope ADMINは404（越境・存在オラクル是正 W2）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"));
        }

        @Test
        @DisplayName("非メンバーは404（越境・存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"));
        }

        /**
         * AC-1: 越境（非所属）者が実在idを叩いた応答と不在idを叩いた応答が一致すること。
         * 同一チーム内の権限不足（SHIFT_019）とは区別する（AC-2）。
         */
        @Test
        @DisplayName("★AC-1: 非メンバーの他チーム依頼idと不在idは同一応答")
        void AC1_非メンバーの他チーム依頼idと不在idは同一応答() throws Exception {
            setAuth(outsiderId);
            var crossTeam = mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"))
                    .andReturn();
            var absent = mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_030"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(crossTeam.getResponse().getStatus())
                    .isEqualTo(absent.getResponse().getStatus());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 存在オラクル是正 W2 補完（AC-1 message / AC-3 / AC-10 / AC-11）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 存在オラクル是正 W2 補完")
    class OracleCompletion {

        private static final List<String> KINDS = List.of("list", "create", "review", "get", "withdraw");

        private MvcResult call(String kind, Long id) throws Exception {
            return switch (kind) {
                case "list" -> mockMvc.perform(get("/api/v1/shifts/change-requests")
                        .param("scheduleId", id.toString())).andReturn();
                case "create" -> mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/shifts/change-requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("scheduleId", id, "requestType", "OPEN_CALL", "reason", "x"))))
                        .andReturn();
                case "review" -> mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "ACCEPTED", "reviewComment", "x", "version", 0)))).andReturn();
                case "get" -> mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", id)).andReturn();
                default -> mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", id)).andReturn();
            };
        }

        private Long realId(String kind) {
            return switch (kind) {
                case "list", "create" -> scheduleAId;
                default -> myRequestId;
            };
        }

        @Test
        @DisplayName("AC-1: 越境（別scope ADMIN・非メンバー）の実在IDと不在IDで status・code・message が一致（全EP）")
        void AC1_全EPでstatusとcodeとmessageが一致() throws Exception {
            for (Long actor : List.of(adminTeamBId, outsiderId)) {
                for (String kind : KINDS) {
                    setAuth(actor);
                    assertSameError(call(kind, realId(kind)), call(kind, 999_999_999L));
                }
            }
        }

        @Test
        @DisplayName("AC-10: ID 境界値（0・-1・Long.MAX_VALUE）は不在IDと同じ応答（全EP）")
        void AC10_ID境界値は不在IDと同じ応答() throws Exception {
            setAuth(adminTeamBId);
            for (String kind : KINDS) {
                MvcResult absent = call(kind, 999_999_999L);
                for (Long id : BOUNDARY_IDS) {
                    assertSameError(call(kind, id), absent);
                }
            }
        }

        @Test
        @DisplayName("AC-9/AC-10: scheduleId 省略は400・数値でないパスIDは400（500にならない）")
        void AC9_AC10_省略と非数値は400() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")).andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", "abc")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-3: user_roles のみの ADMIN は一覧(全件)・審査が許可、取下げは本人以外なので SHIFT_019、作成は所属者として403（404に化けない）")
        void AC3_userRolesOnlyAdmin() throws Exception {
            setAuth(userRolesOnlyAdminAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
            mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("decision", "ACCEPTED", "reviewComment", "x", "version", 0))))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/api/v1/shifts/change-requests/{id}", othersRequestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_019"));
            org.assertj.core.api.Assertions.assertThat(call("create", scheduleAId).getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("AC-5: 非メンバーの SYSTEM_ADMIN は一覧・審査できる")
        void AC5_systemAdminは通る() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/v1/shifts/change-requests/{id}/review", myRequestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("decision", "ACCEPTED", "reviewComment", "x", "version", 0))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-11: 越境で404にした書込み（作成・審査・取下げ）は DB を一切変えない")
        void AC11_越境書込でDB不変() throws Exception {
            long before = changeRequestRepository.count();
            setAuth(adminTeamBId);
            for (String kind : List.of("create", "review", "withdraw")) {
                call(kind, realId(kind));
            }
            em.flush();
            em.clear();
            org.assertj.core.api.Assertions.assertThat(changeRequestRepository.count()).isEqualTo(before);
            ShiftChangeRequestEntity after = changeRequestRepository.findById(myRequestId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(after.getStatus()).isEqualTo(ChangeRequestStatus.OPEN);
            org.assertj.core.api.Assertions.assertThat(after.getVersion()).isEqualTo(0L);
        }
    }

    /** AC-1: status・error.code・error.message の3つすべてが一致すること。 */
    private static void assertSameError(MvcResult crossTeam, MvcResult absent) throws Exception {
        String a = crossTeam.getResponse().getContentAsString();
        String b = absent.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(crossTeam.getResponse().getStatus())
                .isEqualTo(absent.getResponse().getStatus());
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(a, "$.error.code"))
                .isEqualTo(JsonPath.read(b, "$.error.code"));
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(a, "$.error.message"))
                .isEqualTo(JsonPath.read(b, "$.error.message"));
    }

    private static final List<Long> BOUNDARY_IDS = List.of(0L, -1L, Long.MAX_VALUE);

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
                                + "VALUES (:email, 'WAVE6', 'テスト', 'WAVE6 テスト', 'ACTIVE', "
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
