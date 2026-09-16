package com.mannschaft.app.jobmatching;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB11 — jobmatching（求人マッチング）ドメイン
 * 詳細取得 API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code JobPostingController#getJob}（→ {@code JobPostingService#findById}）は
 * 一覧（{@code searchJobs}）が {@code ContentVisibilityChecker} で viewer 視点フィルタしているのに対し、
 * 詳細取得は存在確認のみで可視性を見ておらず、id 直打ちで他チームの DRAFT を含む求人が閲覧できていた。
 * また {@code JobApplicationController#getApplication}（→ {@code JobApplicationService#findById}）は
 * 「MVP では認可チェックを行わない」実装コメントの通り認証済みであれば誰でも他人の応募
 * （自己PR含む）を閲覧できる BOLA が成立していた。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非権限（求人=非メンバー／応募=無関係の第三者）403 / 越境 BOLA（別チームの
 * ADMIN・メンバーが id 直打ちで他チームのリソースへアクセス）403 / 正当（チームメンバー・
 * 応募者本人・採否権限者）200。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("jobmatching（求人マッチング）ドメイン 詳細取得 認可契約テスト（試練・Wave3-B11）")
class JobDetailScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;      // TEAM A の ADMIN（求人作成者・採否権限者）
    private Long adminTeamBId;      // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;     // TEAM A の非 ADMIN メンバー（応募者本人）
    private Long otherMemberTeamAId; // TEAM A の非 ADMIN メンバー（応募と無関係の第三者）
    private Long outsiderId;        // どこにも所属しない非メンバー

    private Long postingTeamAId;    // TEAM A・OPEN・TEAM_MEMBERS 公開範囲の求人
    private Long applicationId;     // memberTeamAId による応募

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("JOBAUTHZ チームA");
        teamBId = insertTeam("JOBAUTHZ チームB");

        adminTeamAId = insertUser("jobauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("jobauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("jobauthz-member-team-a@example.com");
        otherMemberTeamAId = insertUser("jobauthz-other-member-team-a@example.com");
        outsiderId = insertUser("jobauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership 系（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        LocalDateTime now = LocalDateTime.now();
        JobPostingEntity posting = jobPostingRepository.save(JobPostingEntity.builder()
                .teamId(teamAId)
                .createdByUserId(adminTeamAId)
                .title("JOBAUTHZ 求人")
                .description("JOBAUTHZ 求人説明")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("JOBAUTHZ 会場")
                .workStartAt(now.plusDays(3))
                .workEndAt(now.plusDays(3).plusHours(4))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(1)
                .applicationDeadlineAt(now.plusDays(2))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(JobPostingStatus.OPEN)
                .build());
        postingTeamAId = posting.getId();

        JobApplicationEntity application = jobApplicationRepository.save(JobApplicationEntity.builder()
                .jobPostingId(postingTeamAId)
                .applicantUserId(memberTeamAId)
                .selfPr("JOBAUTHZ 自己PR（秘匿対象）")
                .status(JobApplicationStatus.APPLIED)
                .appliedAt(now)
                .build());
        applicationId = application.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /api/v1/jobs/{id}（求人詳細）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /api/v1/jobs/{id}（求人詳細）")
    class GetJob {

        @Test
        @DisplayName("非権限: 非メンバーは403")
        void 非権限は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/jobs/{id}", postingTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがid直打ちでteamAの求人を閲覧しようとすると403")
        void 越境BOLAは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/jobs/{id}", postingTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当: 同チームメンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/jobs/{id}", postingTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /api/v1/applications/{id}（応募詳細）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /api/v1/applications/{id}（応募詳細）")
    class GetApplication {

        @Test
        @DisplayName("非権限: 応募と無関係の同チーム第三者は404（自己PRの覗き見を阻止・存在も秘匿）")
        void 非権限は404() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがid直打ちで他チームの応募を閲覧しようとしても、不在時と同じ404")
        void 越境BOLAは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("存在オラクル: 不在の応募IDも越境の応募IDも同じ404（応答差で実在を判別できない）")
        void 不在と越境は同一応答() throws Exception {
            setAuth(adminTeamBId);
            // 越境（実在するが他チームの応募）
            mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                    .andExpect(status().isNotFound());
            // 不在（存在しない応募ID）
            mockMvc.perform(get("/api/v1/applications/{id}", 999_999_999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当: 応募者本人は200")
        void 応募者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当: 求人の採否権限者（求人先チームADMIN）は200")
        void 採否権限者は200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/applications/{id}", applicationId))
                    .andExpect(status().isOk());
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
                                + "VALUES (:email, 'JOBAUTHZ', 'テスト', 'JOBAUTHZ テスト', 'ACTIVE', "
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
