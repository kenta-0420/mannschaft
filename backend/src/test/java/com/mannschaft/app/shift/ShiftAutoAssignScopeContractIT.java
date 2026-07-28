package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
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
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — shift ドメイン（自動割当 {@code ShiftAutoAssignController}）認可契約テスト。
 *
 * <p><b>検証範囲:</b> {@code ShiftAutoAssignService} が {@code AccessControlService} を用いて
 * 全 public メソッド（実行・確定・破棄・履歴閲覧）に per-scope 管理者認可を強制していることを、
 * {@code scheduleId} / {@code runId} 経由の各エンドポイントで検証する。</p>
 *
 * <p><b>とりわけ重要な検証（2 段階の独立防御）:</b> 確定 API は「run が
 * {@code CONFIRMED}（＝目視確認済み）でなければ {@code VISUAL_REVIEW_REQUIRED}」という
 * 事前条件を持つ。目視確認 API と確定 API の<b>両者が独立に認可拒否されること</b>を
 * {@link TwoStepAttack} で明示検証する（一方の認可がもう一方の防御を代替しないことを保証する）。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT} / {@code ShiftChangeRequestScopeContractIT}（同ドメイン）。</p>
 *
 * <p><b>応答コードの方針:</b></p>
 * <ul>
 *   <li>パスにスコープを持つ {@code /schedules/&#123;scheduleId&#125;/...} 系 … 非 ADMIN は 403、
 *       body の runId がパスのスケジュールに属さない場合は 404（存在秘匿）</li>
 *   <li>パスにスコープを持たない {@code /assignment-runs/&#123;runId&#125;} 系 … 越境は 404（存在秘匿。
 *       403 と撃ち分けると runId の実在が観測できるため）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（自動割当）認可契約テスト")
class ShiftAutoAssignScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftAssignmentRunRepository runRepository;

    @Autowired
    private ShiftAssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long scheduleAId;
    private Long scheduleBId;
    private Long slotAId;

    /** SUCCEEDED（目視確認前）の run。confirm-visual-review の対象。 */
    private Long succeededRunAId;
    /** SUCCEEDED の run（revoke 用に分離。revoke は run の状態を壊すため使い回さない）。 */
    private Long revokableRunAId;
    /** CONFIRMED（目視確認済み）の run。confirm の対象。 */
    private Long confirmedRunAId;
    /** confirmedRunA にぶら下がる PROPOSED 割当。 */
    private Long proposedAssignmentAId;
    /** TEAM B 側の run（越境検証で TEAM A の URL に混ぜる用）。 */
    private Long runBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE7 自動割当 チームA");
        teamBId = insertTeam("WAVE7 自動割当 チームB");

        adminTeamAId = insertUser("wave7-aa-admin-team-a@example.com");
        adminTeamBId = insertUser("wave7-aa-admin-team-b@example.com");
        memberTeamAId = insertUser("wave7-aa-member-team-a@example.com");
        outsiderId = insertUser("wave7-aa-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        scheduleAId = insertSchedule(teamAId, "WAVE7 自動割当 スケジュールA", adminTeamAId);
        scheduleBId = insertSchedule(teamBId, "WAVE7 自動割当 スケジュールB", adminTeamBId);

        slotAId = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleAId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(12, 0))
                .requiredCount(1)
                .build()).getId();

        succeededRunAId = insertRun(scheduleAId, ShiftAssignmentRunStatus.SUCCEEDED, adminTeamAId);
        revokableRunAId = insertRun(scheduleAId, ShiftAssignmentRunStatus.SUCCEEDED, adminTeamAId);
        confirmedRunAId = insertRun(scheduleAId, ShiftAssignmentRunStatus.CONFIRMED, adminTeamAId);
        runBId = insertRun(scheduleBId, ShiftAssignmentRunStatus.SUCCEEDED, adminTeamBId);

        proposedAssignmentAId = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(slotAId)
                .userId(memberTeamAId)
                .runId(confirmedRunAId)
                .status(ShiftAssignmentStatus.PROPOSED)
                .assignedBy(adminTeamAId)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. POST /shifts/schedules/{scheduleId}/auto-assign（実行・管理操作）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /shifts/schedules/{scheduleId}/auto-assign（自動割当実行）")
    class RunAutoAssign {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（管理操作）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201（機能非回帰）")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> runBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("strategy", AssignmentStrategyType.GREEDY_V1.name());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /shifts/schedules/{scheduleId}/auto-assign/confirm（確定・書込）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /shifts/schedules/{scheduleId}/auto-assign/confirm（提案確定）")
    class ConfirmAutoAssign {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA・他チームの勤務表書換の封鎖）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（管理操作）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINでも他スケジュールのrunIdは404（BOLA・パス↔body突合）")
        void 正当ADMINでも他スケジュールのrunIdは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(runBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. DELETE /shifts/schedules/{scheduleId}/auto-assign（破棄・業務妨害）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DELETE /shifts/schedules/{scheduleId}/auto-assign（提案破棄）")
    class RevokeAutoAssign {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINでも他スケジュールのrunIdは404（BOLA）")
        void 正当ADMINでも他スケジュールのrunIdは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(runBId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204（機能非回帰）")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunAId)))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /shifts/schedules/{scheduleId}/assignment-runs（履歴一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /shifts/schedules/{scheduleId}/assignment-runs（実行履歴一覧）")
    class GetAssignmentRuns {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（管理者専用の運用情報）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /shifts/assignment-runs/{runId}（詳細＝メンバー userId 一覧・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /shifts/assignment-runs/{runId}（実行ログ詳細）")
    class GetAssignmentRunDetail {

        @Test
        @DisplayName("非メンバーは404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは404（存在秘匿）")
        void 非ADMINメンバーは404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST /shifts/assignment-runs/{runId}/confirm-visual-review（目視確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST /shifts/assignment-runs/{runId}/confirm-visual-review（目視確認完了）")
    class ConfirmVisualReview {

        @Test
        @DisplayName("非メンバーは404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINは404（BOLA・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは404（存在秘匿）")
        void 非ADMINメンバーは404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200（機能非回帰）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. ★2段攻撃★ 目視確認 → 確定 の連鎖が成立しないこと
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 本 PR の主眼。目視確認 API と確定 API は連続する 2 つの操作だが、認可は互いに独立して
     * 効いていなければならない（一方の認可が他方の前提を代替してはならない）。
     * <b>2 段の各段が独立に拒否されること</b>と、<b>一方を実行しても run の状態が
     * SUCCEEDED のまま（＝確定の前提が満たされない）こと</b>を検証する。
     */
    @Nested
    @DisplayName("7. 2段攻撃（目視確認→確定）が成立しない")
    class TwoStepAttack {

        @Test
        @DisplayName("別scope ADMIN: 第1段（目視確認）は404で弾かれ、runは SUCCEEDED のまま")
        void 第1段が弾かれrun状態が変わらない() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "乗っ取り"))))
                    .andExpect(status().isNotFound());

            // 認可で弾かれた後は永続化コンテキストを捨てて DB の実状態を読み直す
            // （flush は呼ばない。拒否された tx は rollbackOnly のため書き戻さない）。
            em.clear();
            ShiftAssignmentRunEntity after = runRepository.findById(succeededRunAId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(after.getStatus())
                    .as("目視確認が弾かれたので CONFIRMED に昇格していないこと")
                    .isEqualTo(ShiftAssignmentRunStatus.SUCCEEDED);
            org.assertj.core.api.Assertions.assertThat(after.getVisualReviewConfirmedBy())
                    .as("越境ユーザーが確認者として記録されていないこと")
                    .isNull();
        }

        @Test
        @DisplayName("別scope ADMIN: 第2段（確定）も独立に403で弾かれ、割当は PROPOSED のまま")
        void 第2段も独立に弾かれ割当が確定しない() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isForbidden());

            em.clear();
            ShiftAssignmentEntity after = assignmentRepository.findById(proposedAssignmentAId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(after.getStatus())
                    .as("確定が弾かれたので PROPOSED のままであること")
                    .isEqualTo(ShiftAssignmentStatus.PROPOSED);
        }

        @Test
        @DisplayName("非ADMINメンバー: 2段とも弾かれる（403/404の撃ち分けは経路仕様どおり）")
        void 非ADMINメンバーも2段とも弾かれる() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "昇格試行"))))
                    .andExpect(status().isNotFound());

            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunAId))))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> confirmBody(Long runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("assignmentIds", List.of(proposedAssignmentAId));
        body.put("scheduleVersion", 0);
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertSchedule(Long teamId, String title, Long createdBy) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long insertRun(Long scheduleId, ShiftAssignmentRunStatus status, Long triggeredBy) {
        return runRepository.save(ShiftAssignmentRunEntity.builder()
                .scheduleId(scheduleId)
                .strategy(AssignmentStrategyType.GREEDY_V1)
                .status(status)
                .triggeredBy(triggeredBy)
                .slotsTotal(1)
                .slotsFilled(0)
                .build()).getId();
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
                                + "VALUES (:email, 'WAVE7', 'テスト', 'WAVE7 テスト', 'ACTIVE', "
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
