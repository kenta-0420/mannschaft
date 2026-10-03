package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-027（試練・先行 red）— 下向き再帰の所属判定を <b>memberships</b> 一系統で成立させる。
 *
 * <p><b>背景</b>: {@code V60.010} で {@code user_roles} から MEMBER/SUPPORTER 行は削除され
 * {@code memberships} へ完全移行した。ところが {@link UserRoleRepository#findDescendantMembershipRolesByOrgRoots}
 * と {@link UserRoleRepository#countUserInOrganizationDescendants} は所属判定を
 * {@code user_roles} 一色で行っているため、<b>本番で唯一成立しうる「memberships だけを持つ素メンバー」</b>
 * を取りこぼす（下向き再帰配下の可視性・配信母集団が壊れる）。</p>
 *
 * <p><b>フィクスチャの鉄則</b>: 本クラスの素メンバー／応援者は <b>memberships のみ</b>で表現する
 * （{@code user_roles} に MEMBER/SUPPORTER 行は張らない＝本番不能な状態を作らない）。
 * よって実装修正前は下向き再帰が空を返し、A1/A2/A3/A9 の各テストは <b>red</b> になる。
 * 一方 A4/A5/A7 の漏洩防止系は「余計なものを返さない」ことを固定する番人であり、
 * 修正の前後を通じて green であるべき（正しい修正が漏洩を生まないことの保証）。</p>
 *
 * <p>構成は {@link UserRoleDistributionRecursiveRepositoryTest} を踏襲し、
 * {@code @Transactional} ロールバック + Testcontainers MySQL（{@code AbstractMySqlIntegrationTest}）。
 * {@code @EnabledIf} は JUnit5 非継承のため派生側で再宣言する。</p>
 */
@Transactional
@DisplayName("UserRoleRepository 下向き再帰 memberships-only 所属判定（CMP-027 試練）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleDescendantMembershipOnlyRepositoryTest extends AbstractMySqlIntegrationTest {

    private static final int MAX_DEPTH = 32;

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー（memberships-only の素メンバーを作るためのもの）
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    /** 組織を永続化する（親 ID 指定可能）。返り値は採番された ID。 */
    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("c27-org-" + n)
                .name("CMP027テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        return org.getId();
    }

    private Long persistActiveUser() {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("c27-user-" + n + "@example.com")
                .lastName("配下")
                .firstName("素メンバー" + n)
                .displayName("配下素メンバー" + n)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private void linkTeamToOrg(Long teamId, Long organizationId, TeamOrgMembershipEntity.Status status) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(status)
                .invitedAt(java.time.LocalDateTime.now())
                .build();
        em.persist(tom);
    }

    /**
     * memberships のみで素メンバー／応援者の所属を作る（user_roles には一切書かない）。
     * これが本番で唯一成立しうる形（V60.010 移行後）。
     */
    private void addActiveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        MembershipTestHelper.insertMembership(em, userId, scopeType, scopeId, roleKind);
    }

    /** 退会済み（left_at IS NOT NULL）の membership を張る（A5/A7 の漏洩番人用）。 */
    private void addLeftMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        em.createNativeQuery(
                "INSERT INTO memberships ("
                        + "user_id, scope_type, scope_id, role_kind, "
                        + "joined_at, left_at, leave_reason, invited_by, "
                        + "created_at, updated_at) "
                        + "VALUES (:uid, :st, :sid, :rk, "
                        + "NOW(), NOW(), 'SELF', NULL, "
                        + "NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("rk", roleKind.name())
                .executeUpdate();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /** バルク版の戻りから根 ORG ID だけを取り出すヘルパー。 */
    private List<Long> matchedRootIds(Set<Long> rootOrgIds, Long userId) {
        return userRoleRepository.findDescendantMembershipRolesByOrgRoots(rootOrgIds, userId, MAX_DEPTH).stream()
                .map(UserRoleRepository.DescendantMembershipRoleProjection::getRootOrgId)
                .distinct()
                .toList();
    }

    // =====================================================================
    // A1: 正 — memberships のみの素メンバーが根 ORG を返す（現状 empty ゆえ red）
    // =====================================================================

    @Test
    @DisplayName("ac_a1_配下ACTIVEチームにmembershipのみのMEMBERは根ORGをroleName=MEMBERで返す")
    void ac_a1_配下チームmembershipのみのMEMBERが根を返す() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 700_001L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // memberships のみ（user_roles 行なし）。これが V60.010 後の本番の素メンバー。
        Long plainMember = persistActiveUser();
        addActiveMembership(plainMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER);
        flushClear();

        List<UserRoleRepository.DescendantMembershipRoleProjection> rows =
                userRoleRepository.findDescendantMembershipRolesByOrgRoots(Set.of(rootOrg), plainMember, MAX_DEPTH);

        assertThat(rows)
                .as("memberships のみの配下チーム MEMBER が下向き再帰で拾えていない（CMP-027 本丸）")
                .isNotEmpty();
        assertThat(rows).extracting(UserRoleRepository.DescendantMembershipRoleProjection::getRootOrgId)
                .containsOnly(rootOrg);
        assertThat(rows).extracting(UserRoleRepository.DescendantMembershipRoleProjection::getRoleName)
                .contains("MEMBER");
    }

    @Test
    @DisplayName("ac_a1b_配下組織直属にmembershipのみのMEMBERも根ORGを返す")
    void ac_a1b_配下組織直属membershipのみのMEMBERが根を返す() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        // 配下組織（leafOrg）に ORGANIZATION スコープの memberships のみ
        Long orgDirectMember = persistActiveUser();
        addActiveMembership(orgDirectMember, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER);
        flushClear();

        assertThat(matchedRootIds(Set.of(rootOrg), orgDirectMember))
                .as("memberships のみの配下組織直属 MEMBER が下向き再帰で拾えていない")
                .containsExactly(rootOrg);
    }

    // =====================================================================
    // A2: 複数経路 → priority 最小（MEMBER）が返り値に含まれる（現状 empty ゆえ red）
    // =====================================================================

    @Test
    @DisplayName("ac_a2_同一根に複数経路(MEMBER+SUPPORTER)ならMEMBER行が返る(priority最小へ畳み込む材料)")
    void ac_a2_複数経路はMEMBER行を含む() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long teamMember = 700_010L;
        Long teamSupporter = 700_011L;
        linkTeamToOrg(teamMember, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        linkTeamToOrg(teamSupporter, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 同一ユーザーが片方の配下チームで MEMBER、別の配下チームで SUPPORTER（memberships のみ）
        Long mixed = persistActiveUser();
        addActiveMembership(mixed, ScopeType.TEAM, teamMember, RoleKind.MEMBER);
        addActiveMembership(mixed, ScopeType.TEAM, teamSupporter, RoleKind.SUPPORTER);
        flushClear();

        List<String> roleNames = userRoleRepository
                .findDescendantMembershipRolesByOrgRoots(Set.of(rootOrg), mixed, MAX_DEPTH).stream()
                .map(UserRoleRepository.DescendantMembershipRoleProjection::getRoleName)
                .toList();

        assertThat(roleNames)
                .as("複数経路の畳み込み材料として少なくとも MEMBER 行が返らなければ priority 最小へ寄せられない")
                .contains("MEMBER");
    }

    // =====================================================================
    // A3: maxDepth 境界（現状 empty ゆえ「境界内で含む」が red、「境界外で除外」は番人）
    // =====================================================================

    @Test
    @DisplayName("ac_a3_maxDepth境界_深さ内のmembershipのみメンバーは含み_打ち切り深より深いと除外")
    void ac_a3_maxDepth境界で打ち切る() {
        // root(0) -> mid(1) -> leaf(2)。leaf 組織に membership-only メンバーを置く。
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        Long deepMember = persistActiveUser();
        addActiveMembership(deepMember, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER);
        flushClear();

        // 深さ十分（32）なら含む（← 実装修正前は empty ゆえ red）
        assertThat(matchedRootIds(Set.of(rootOrg), deepMember))
                .as("maxDepth=32 なら深さ2の配下 membership メンバーを含むべし")
                .containsExactly(rootOrg);

        // maxDepth=1 は leaf(深さ2)まで展開しないため除外される（番人・打ち切り）
        List<Long> shallow = userRoleRepository
                .findDescendantMembershipRolesByOrgRoots(Set.of(rootOrg), deepMember, 1).stream()
                .map(UserRoleRepository.DescendantMembershipRoleProjection::getRootOrgId)
                .distinct().toList();
        assertThat(shallow)
                .as("maxDepth=1 では深さ2の配下は展開されず算入されない")
                .isEmpty();
    }

    // =====================================================================
    // A4: 空/0件（番人・正）— membership が対象ツリー外なら空
    // =====================================================================

    @Test
    @DisplayName("ac_a4_対象ツリー外にしかmembershipが無いユーザーは空を返す(誤検出しない番人)")
    void ac_a4_ツリー外membershipのみは空() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 700_020L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 無関係な別ツリー
        Long otherOrg = persistOrganization(null);
        Long otherTeam = 700_021L;
        linkTeamToOrg(otherTeam, otherOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long outsider = persistActiveUser();
        addActiveMembership(outsider, ScopeType.TEAM, otherTeam, RoleKind.MEMBER);
        flushClear();

        assertThat(matchedRootIds(Set.of(rootOrg), outsider))
                .as("rootOrg 配下に所属しないユーザーを誤って算入してはならない")
                .isEmpty();
    }

    // =====================================================================
    // A5: 退会（left_at IS NOT NULL）は除外（番人）
    // =====================================================================

    @Test
    @DisplayName("ac_a5_退会済(left_at IS NOT NULL)membershipは配下所属に算入されない")
    void ac_a5_退会membershipは除外() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 700_030L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 退会済みメンバー（left_at 設定）。user_roles は無い。
        Long leftMember = persistActiveUser();
        addLeftMembership(leftMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER);
        flushClear();

        assertThat(matchedRootIds(Set.of(rootOrg), leftMember))
                .as("退会済み membership は在籍でないため配下所属に含めてはならない")
                .isEmpty();
    }

    // =====================================================================
    // A7: 漏洩防止（最重要・番人）— 非ACTIVEチーム/退会/ツリー外は返らない
    // =====================================================================

    @Test
    @DisplayName("ac_a7_非ACTIVEチームのmembershipのみメンバーは配下所属に漏れない")
    void ac_a7_非ACTIVEチームは漏洩しない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long pendingTeam = 700_040L;
        // team_org_memberships が PENDING（未承認）
        linkTeamToOrg(pendingTeam, leafOrg, TeamOrgMembershipEntity.Status.PENDING);

        Long member = persistActiveUser();
        addActiveMembership(member, ScopeType.TEAM, pendingTeam, RoleKind.MEMBER);
        flushClear();

        assertThat(matchedRootIds(Set.of(rootOrg), member))
                .as("非 ACTIVE な team_org_memberships 配下のメンバーを漏らしてはならない")
                .isEmpty();
    }

    // =====================================================================
    // A9: M1 — countUserInOrganizationDescendants も memberships-only を数える（red）
    // =====================================================================

    @Test
    @DisplayName("ac_a9_M1_countUserInOrganizationDescendantsはmembershipのみメンバーをcount1以上で数える")
    void ac_a9_M1_membershipのみメンバーをcountで数える() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);
        Long leafTeam = 700_050L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long teamMember = persistActiveUser();
        addActiveMembership(teamMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER);

        Long orgDirect = persistActiveUser();
        addActiveMembership(orgDirect, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER);

        Long outsider = persistActiveUser();
        Long otherOrg = persistOrganization(null);
        addActiveMembership(outsider, ScopeType.ORGANIZATION, otherOrg, RoleKind.MEMBER);
        flushClear();

        assertThat(userRoleRepository.countUserInOrganizationDescendants(rootOrg, teamMember, MAX_DEPTH))
                .as("M1: memberships のみの配下チーム MEMBER を数えられていない")
                .isGreaterThanOrEqualTo(1L);
        assertThat(userRoleRepository.countUserInOrganizationDescendants(rootOrg, orgDirect, MAX_DEPTH))
                .as("M1: memberships のみの配下組織直属 MEMBER を数えられていない")
                .isGreaterThanOrEqualTo(1L);
        // 番人: 無関係ユーザーは 0
        assertThat(userRoleRepository.countUserInOrganizationDescendants(rootOrg, outsider, MAX_DEPTH))
                .as("無関係ユーザーを数えてはならない")
                .isZero();
    }
}
