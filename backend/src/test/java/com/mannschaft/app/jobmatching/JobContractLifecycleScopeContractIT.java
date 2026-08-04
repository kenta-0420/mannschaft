package com.mannschaft.app.jobmatching;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第2波 — jobmatching（求人マッチング）ドメイン
 * 契約ライフサイクル・QR・自己スコープ EP の認可契約テスト。
 *
 * <p>本テストが固定する防御仕様:</p>
 * <ul>
 *   <li><b>実体由来の当事者判定</b>: 契約 ID を受け取る EP は対象契約を実体としてロードし、
 *       契約行の {@code requester_user_id} / {@code worker_user_id} と認証主体を照合する。
 *       当事者以外は 403（{@code JOB_PERMISSION_DENIED} /
 *       {@code JOB_QR_TOKEN_WRONG_WORKER}）で拒否される。</li>
 *   <li><b>自己スコープ</b>: 一覧系 EP の検索条件は認証主体の userId のみに束縛され、
 *       他人の契約・応募は結果に混入しない。</li>
 * </ul>
 *
 * <p>本テストは以下の自己スコープ宣言（{@code @SelfScopedEndpoint}）の証跡を兼ねる:
 * {@code JobContractController#listMyContracts} /
 * {@code JobApplicationController#listMyApplications} /
 * {@code JobPostingController#previewFee}。</p>
 *
 * <p>金型: {@code JobDetailScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("jobmatching 契約ライフサイクル・QR・自己スコープ 認可契約テスト（第2波）")
class JobContractLifecycleScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @Autowired
    private JobContractRepository jobContractRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    /** 契約の Requester（求人作成者・採否権限者）。 */
    private Long requesterId;
    /** 契約の Worker（応募者本人）。 */
    private Long workerId;
    /** 契約にも応募にも関与しない第三者（同チーム所属）。 */
    private Long outsiderId;

    private Long postingId;
    private Long applicationId;
    private Long contractId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("JOBLIFE チーム");

        requesterId = insertUser("joblife-requester@example.com");
        workerId = insertUser("joblife-worker@example.com");
        outsiderId = insertUser("joblife-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership 系（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（JobDetailScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, requesterId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, requesterId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, outsiderId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        LocalDateTime now = LocalDateTime.now();
        JobPostingEntity posting = jobPostingRepository.save(JobPostingEntity.builder()
                .teamId(teamId)
                .createdByUserId(requesterId)
                .title("JOBLIFE 求人")
                .description("JOBLIFE 求人説明")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("JOBLIFE 会場")
                .workStartAt(now.plusDays(3))
                .workEndAt(now.plusDays(3).plusHours(4))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(1)
                .applicationDeadlineAt(now.plusDays(2))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(JobPostingStatus.OPEN)
                .build());
        postingId = posting.getId();

        JobApplicationEntity application = jobApplicationRepository.save(JobApplicationEntity.builder()
                .jobPostingId(postingId)
                .applicantUserId(workerId)
                .selfPr("JOBLIFE 自己PR（秘匿対象）")
                .status(JobApplicationStatus.APPLIED)
                .appliedAt(now)
                .build());
        applicationId = application.getId();

        JobContractEntity contract = jobContractRepository.save(JobContractEntity.builder()
                .jobPostingId(postingId)
                .jobApplicationId(applicationId)
                .requesterUserId(requesterId)
                .workerUserId(workerId)
                .baseRewardJpy(3000)
                .workStartAt(now.plusDays(3))
                .workEndAt(now.plusDays(3).plusHours(4))
                .status(JobContractStatus.MATCHED)
                .matchedAt(now)
                .build());
        contractId = contract.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 契約 ID を受け取る EP — 当事者以外は 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. JobContractController — 当事者（Requester / Worker）以外は 403")
    class ContractParticipantOnly {

        @Test
        @DisplayName("GET /api/v1/contracts/{id}: 第三者は403（JobContractController#getContract）")
        void 第三者の契約詳細取得は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/contracts/{id}", contractId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/v1/contracts/{id}: 当事者（Worker本人）は200")
        void 当事者の契約詳細取得は200() throws Exception {
            setAuth(workerId);
            mockMvc.perform(get("/api/v1/contracts/{id}", contractId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST .../report-completion: Worker 以外は403（JobContractController#reportCompletion）")
        void 第三者の完了報告は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/contracts/{id}/report-completion", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"侵入試行\"}"))
                    .andExpect(status().isForbidden());
            assertContractStatusUnchanged();
        }

        @Test
        @DisplayName("POST .../report-completion: Requester（Worker ではない当事者）も403")
        void 依頼者の完了報告は403() throws Exception {
            setAuth(requesterId);
            mockMvc.perform(post("/api/v1/contracts/{id}/report-completion", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"依頼者による報告\"}"))
                    .andExpect(status().isForbidden());
            assertContractStatusUnchanged();
        }

        @Test
        @DisplayName("POST .../approve-completion: Requester 以外は403（JobContractController#approveCompletion）")
        void 第三者の完了承認は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/contracts/{id}/approve-completion", contractId))
                    .andExpect(status().isForbidden());
            assertContractStatusUnchanged();
        }

        @Test
        @DisplayName("POST .../reject-completion: Requester 以外は403（JobContractController#rejectCompletion）")
        void 第三者の完了差し戻しは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/contracts/{id}/reject-completion", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"侵入試行\"}"))
                    .andExpect(status().isForbidden());
            assertContractStatusUnchanged();
        }

        @Test
        @DisplayName("POST .../cancel: 当事者以外は403（JobContractController#cancelContract）")
        void 第三者のキャンセルは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/contracts/{id}/cancel", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"侵入試行\"}"))
                    .andExpect(status().isForbidden());
            assertContractStatusUnchanged();
        }

        /** 拒否時に契約状態が一切動いていないことを DB の実値で確認する。 */
        private void assertContractStatusUnchanged() {
            em.flush();
            em.clear();
            JobContractEntity reloaded = jobContractRepository.findById(contractId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(reloaded.getStatus())
                    .as("拒否された操作で契約ステータスが動いていないこと")
                    .isEqualTo(JobContractStatus.MATCHED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 応募取り下げ — 応募者本人以外は 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. JobApplicationController#withdrawApplication — 応募者本人以外は 403")
    class WithdrawApplication {

        @Test
        @DisplayName("POST /api/v1/applications/{id}/withdraw: 第三者は403")
        void 第三者の取り下げは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/applications/{id}/withdraw", applicationId))
                    .andExpect(status().isForbidden());

            em.flush();
            em.clear();
            JobApplicationEntity reloaded = jobApplicationRepository.findById(applicationId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(reloaded.getStatus())
                    .as("拒否された取り下げで応募ステータスが動いていないこと")
                    .isEqualTo(JobApplicationStatus.APPLIED);
        }

        @Test
        @DisplayName("POST /api/v1/applications/{id}/withdraw: 求人側の採否権限者（Requester）も403")
        void 採否権限者の取り下げは403() throws Exception {
            setAuth(requesterId);
            mockMvc.perform(post("/api/v1/applications/{id}/withdraw", applicationId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. QR トークン — Requester 本人以外は 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. JobQrTokenController — Requester 本人以外は 403")
    class QrToken {

        @Test
        @DisplayName("POST /api/v1/contracts/{id}/qr-tokens: 第三者は403（JobQrTokenController#issue）")
        void 第三者の発行は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"IN\",\"ttlSeconds\":60}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/v1/contracts/{id}/qr-tokens: Worker（Requester ではない当事者）も403")
        void 作業者の発行は403() throws Exception {
            setAuth(workerId);
            mockMvc.perform(post("/api/v1/contracts/{contractId}/qr-tokens", contractId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"IN\",\"ttlSeconds\":60}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/v1/contracts/{id}/qr-tokens/current: 第三者は403（JobQrTokenController#getCurrent）")
        void 第三者の現在トークン取得は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", contractId)
                            .param("type", "IN"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /api/v1/contracts/{id}/qr-tokens/current: Requester 本人は204（未発行）")
        void 依頼者の現在トークン取得は204() throws Exception {
            setAuth(requesterId);
            mockMvc.perform(get("/api/v1/contracts/{contractId}/qr-tokens/current", contractId)
                            .param("type", "IN"))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. チェックイン記録 — Worker 本人以外は 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. JobCheckInController#recordCheckIn — Worker 本人以外は 403")
    class RecordCheckIn {

        @Test
        @DisplayName("POST /api/v1/jobs/check-ins: 第三者は403（トークン検証より前に当事者判定で拒否）")
        void 第三者のチェックインは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(checkInBody()))
                    .andExpect(status().isForbidden());
            assertNoCheckInRecorded();
        }

        @Test
        @DisplayName("POST /api/v1/jobs/check-ins: Requester（Worker ではない当事者）も403")
        void 依頼者のチェックインは403() throws Exception {
            setAuth(requesterId);
            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(checkInBody()))
                    .andExpect(status().isForbidden());
            assertNoCheckInRecorded();
        }

        private String checkInBody() {
            return "{\"contractId\":" + contractId
                    + ",\"shortCode\":\"ZZZZZZ\""
                    + ",\"type\":\"IN\""
                    + ",\"scannedAt\":\"2026-01-01T00:00:00Z\"}";
        }

        /** 拒否時にチェックイン行が 1 件も作られていないことを DB の実値で確認する。 */
        private void assertNoCheckInRecorded() {
            em.flush();
            em.clear();
            Number count = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM job_check_ins WHERE job_contract_id = :cid")
                    .setParameter("cid", contractId)
                    .getSingleResult();
            org.assertj.core.api.Assertions.assertThat(count.longValue())
                    .as("拒否されたチェックインが記録されていないこと")
                    .isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 自己スコープ EP — 他人のデータが混入しない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 自己スコープ EP（他人の契約・応募が結果に混入しない）")
    class SelfScoped {

        @Test
        @DisplayName("GET /api/v1/me/contracts: 第三者には他人の契約が 1 件も返らない"
                + "（JobContractController#listMyContracts）")
        void 第三者の契約一覧は空() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/me/contracts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("GET /api/v1/me/contracts: 当事者には自分の契約のみが返る")
        void 当事者の契約一覧は自分の契約のみ() throws Exception {
            setAuth(workerId);
            mockMvc.perform(get("/api/v1/me/contracts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(contractId));
        }

        @Test
        @DisplayName("GET /api/v1/me/applications: 第三者には他人の応募が 1 件も返らない"
                + "（JobApplicationController#listMyApplications）")
        void 第三者の応募一覧は空() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/me/applications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("GET /api/v1/me/applications: 応募者本人には自分の応募のみが返る")
        void 応募者本人の応募一覧は自分の応募のみ() throws Exception {
            setAuth(workerId);
            mockMvc.perform(get("/api/v1/me/applications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(applicationId));
        }

        @Test
        @DisplayName("GET /api/v1/jobs/fee-preview: 誰が呼んでも同一の純計算結果を返す"
                + "（JobPostingController#previewFee）")
        void 手数料プレビューは呼び手に依存しない() throws Exception {
            setAuth(requesterId);
            String asRequester = mockMvc.perform(get("/api/v1/jobs/fee-preview")
                            .param("baseRewardJpy", "3000"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            setAuth(outsiderId);
            String asOutsider = mockMvc.perform(get("/api/v1/jobs/fee-preview")
                            .param("baseRewardJpy", "3000"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(asOutsider)
                    .as("手数料プレビューは永続データを読まない純計算であり、呼び手により結果が変わらないこと")
                    .isEqualTo(asRequester);
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
                                + "VALUES (:email, 'JOBLIFE', 'テスト', 'JOBLIFE テスト', 'ACTIVE', "
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
