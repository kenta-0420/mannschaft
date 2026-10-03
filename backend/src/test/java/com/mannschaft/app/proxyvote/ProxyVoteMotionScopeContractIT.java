package com.mannschaft.app.proxyvote;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
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

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — proxyvote ドメイン（F08.3 議案の投票開始／終了）API 契約テスト。
 *
 * <p>{@code ProxyVoteMotionController} の兄弟エンドポイントが操作ユーザーを Service へ渡して
 * 認可しているのに対し、{@code startVote} / {@code endVote} は操作ユーザーを受け取らない
 * シグネチャのままで、認可の敷設が未回収だった構造を是正した。</p>
 *
 * <p>敷設した認可は同一ドメインの兄弟（{@code addMotion} / {@code updateMotion} /
 * {@code deleteMotion}）に揃えた {@code checkOwnerOrAdmin}
 * （セッション作成者 <b>または</b> 当該スコープの ADMIN/DEPUTY_ADMIN）。
 * 併せて {@code startAllVotes} も同粒度へ引き上げた（認可判定を後続呼び出しの副作用に委ねず、
 * 書込前の明示的なゲートとして敷き直した）。</p>
 *
 * <p><b>BOLA 検証の要点</b>: これらの EP の URL は {@code /proxy-votes/motions/{motionId}} で
 * path 上にスコープを持たない。したがって認可スコープは必ず
 * 「motionId → 議案 entity → 所属セッション entity 由来の {@code resolveScopeId()}」で解決する。
 * 別スコープの ADMIN が他スコープの議案を操作しようとすると、その entity 由来スコープで
 * 判定されて 403 になる（兄弟 EP と同じ挙動。存在しない ID のみ 404 で秘匿する）。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("proxyvote ドメイン（議案投票制御）認可契約テスト")
class ProxyVoteMotionScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しない ID（既存シードとの衝突を避けるため高位の値を使う）。 */
    private static final long ABSENT_ID = 9_900_000_001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProxyVoteSessionRepository sessionRepository;

    @Autowired
    private ProxyVoteMotionRepository motionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;    // TEAM A の ADMIN 兼 セッション作成者（正当）
    private Long adminTeamBId;    // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;   // TEAM A の非 ADMIN メンバー（非作成者）
    private Long memberCreatorId; // TEAM A の非 ADMIN メンバーだが自分でセッションを作成した人
    private Long outsiderId;      // どこにも所属しない非メンバー

    private Long sessionTeamAId;
    private Long sessionByMemberId;
    private Long sessionTeamBId;

    private Long motionPendingAId;      // TEAM A・PENDING（startVote 用）
    private Long motionVotingAId;       // TEAM A・VOTING（endVote 用）
    private Long motionPendingByMemberId; // 会員が作成したセッションの PENDING 議案
    private Long motionPendingBId;      // TEAM B・PENDING（BOLA 用）
    private Long motionVotingBId;       // TEAM B・VOTING（BOLA 用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("PVAUTHZ チームA");
        teamBId = insertTeam("PVAUTHZ チームB");

        adminTeamAId = insertUser("pvauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("pvauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("pvauthz-member-team-a@example.com");
        memberCreatorId = insertUser("pvauthz-member-creator@example.com");
        outsiderId = insertUser("pvauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberCreatorId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        ProxyVoteSessionEntity sessionTeamA = sessionRepository.save(session(teamAId, adminTeamAId, "PVAUTHZ 総会A"));
        sessionTeamAId = sessionTeamA.getId();

        ProxyVoteSessionEntity sessionByMember =
                sessionRepository.save(session(teamAId, memberCreatorId, "PVAUTHZ 会員作成総会"));
        sessionByMemberId = sessionByMember.getId();

        ProxyVoteSessionEntity sessionTeamB = sessionRepository.save(session(teamBId, adminTeamBId, "PVAUTHZ 総会B"));
        sessionTeamBId = sessionTeamB.getId();

        motionPendingAId = motionRepository.save(
                motion(sessionTeamAId, 1, "PVAUTHZ 議案A1", VotingStatus.PENDING)).getId();
        motionVotingAId = motionRepository.save(
                motion(sessionTeamAId, 2, "PVAUTHZ 議案A2", VotingStatus.VOTING)).getId();
        motionPendingByMemberId = motionRepository.save(
                motion(sessionByMemberId, 1, "PVAUTHZ 議案M1", VotingStatus.PENDING)).getId();
        motionPendingBId = motionRepository.save(
                motion(sessionTeamBId, 1, "PVAUTHZ 議案B1", VotingStatus.PENDING)).getId();
        motionVotingBId = motionRepository.save(
                motion(sessionTeamBId, 2, "PVAUTHZ 議案B2", VotingStatus.VOTING)).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /proxy-votes/motions/{motionId}/start-vote（投票開始）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /proxy-votes/motions/{motionId}/start-vote（投票開始）")
    class StartVote {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）はteamAの議案を開始できず403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("teamAのADMINはteamBの議案を開始できず403（逆方向BOLA・entity由来scopeで判定）")
        void 逆方向BOLAは403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingBId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しない議案IDは404（存在秘匿）")
        void 存在しない議案は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", ABSENT_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("ADMINでなくてもセッション作成者本人は200（checkOwnerOrAdminのowner経路）")
        void 作成者本人は200() throws Exception {
            setAuth(memberCreatorId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/start-vote", motionPendingByMemberId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /proxy-votes/motions/{motionId}/end-vote（投票終了）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH /proxy-votes/motions/{motionId}/end-vote（投票終了）")
    class EndVote {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", motionVotingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", motionVotingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）はteamAの議案を終了できず403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", motionVotingAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("teamAのADMINはteamBの議案を終了できず403（逆方向BOLA・entity由来scopeで判定）")
        void 逆方向BOLAは403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", motionVotingBId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しない議案IDは404（存在秘匿）")
        void 存在しない議案は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", ABSENT_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/motions/{motionId}/end-vote", motionVotingAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PATCH /proxy-votes/{id}/start-all-votes（一括投票開始）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH /proxy-votes/{id}/start-all-votes（一括投票開始）")
    class StartAllVotes {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/start-all-votes", sessionTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/start-all-votes", sessionTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/start-all-votes", sessionTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しないセッションIDは404（存在秘匿）")
        void 存在しないセッションは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/start-all-votes", ABSENT_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/start-all-votes", sessionTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** MEETING モード・OPEN 状態の TEAM スコープセッションを組み立てる。 */
    private ProxyVoteSessionEntity session(Long teamId, Long createdBy, String title) {
        return ProxyVoteSessionEntity.builder()
                .scopeType(ProxyVoteScopeType.TEAM)
                .teamId(teamId)
                .title(title)
                .resolutionMode(ResolutionMode.MEETING)
                .status(SessionStatus.OPEN)
                .meetingDate(LocalDate.now())
                .eligibleCount(3)
                .createdBy(createdBy)
                .build();
    }

    private ProxyVoteMotionEntity motion(Long sessionId, int number, String title, VotingStatus votingStatus) {
        return ProxyVoteMotionEntity.builder()
                .sessionId(sessionId)
                .motionNumber(number)
                .title(title)
                .votingStatus(votingStatus)
                .build();
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
                                + "VALUES (:email, 'PVAUTHZ', 'テスト', 'PVAUTHZ テスト', 'ACTIVE', "
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
