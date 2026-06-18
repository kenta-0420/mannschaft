package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * フェーズM1: 組織配信の再帰的配下解決（universe 再帰化）の結合テスト。
 *
 * <p>{@link UserRoleRepository#findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と
 * {@link UserRoleRepository#existsUserInOrganizationDescendants(Long, Long, int)} を検証する。</p>
 *
 * <ul>
 *   <li>多段配下解決: ネスト組織（root→中間→末端）の末端チームメンバーまで到達することを検証。
 *       1 段版（{@link UserRoleRepository#findDistributionUserIdsForOrganization(Long, boolean)}）では
 *       到達しないことを対比で確認する（回帰防止）。</li>
 *   <li>サイクル防止: parent_organization_id が循環するデータでも maxDepth で停止し、
 *       無限ループしないことを検証。</li>
 *   <li>SUPPORTER 除外の再帰展開: 多段で MEMBER 優先・純 SUPPORTER 除外を検証。</li>
 *   <li>EXISTS 版: 配下のみ所属ユーザー=true / 無関係ユーザー=false。</li>
 * </ul>
 *
 * <p>1 段版テスト（{@link UserRoleDistributionRepositoryTest}）と異なり、再帰版は
 * {@code organizations} テーブルの行（{@code parent_organization_id} 隣接リスト）を実際に
 * 永続化する必要がある（CTE が {@code organizations} を起点に展開するため）。</p>
 */
@Transactional
@DisplayName("UserRoleRepository 組織配信再帰展開 結合テスト（フェーズM1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleDistributionRecursiveRepositoryTest extends AbstractMySqlIntegrationTest {

    private static final int MAX_DEPTH = 32;

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private Long roleId;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    /**
     * 組織を永続化する（親 ID 指定可能）。返り値は採番された ID。
     */
    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("m1-org-" + n)
                .name("M1テスト組織" + n)
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
                .email("m1-user-" + n + "@example.com")
                .lastName("配信")
                .firstName("対象" + n)
                .displayName("配信対象" + n)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private void persistRoleIfNeeded() {
        if (roleId != null) {
            return;
        }
        RoleEntity role = RoleEntity.builder()
                .name("MEMBER")
                .displayName("メンバー")
                .priority(50)
                .isSystem(true)
                .build();
        em.persist(role);
        roleId = role.getId();
    }

    private void grantOrgRole(Long userId, Long organizationId) {
        persistRoleIfNeeded();
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void grantTeamRole(Long userId, Long teamId) {
        persistRoleIfNeeded();
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void linkTeamToOrg(Long teamId, Long organizationId, TeamOrgMembershipEntity.Status status) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build();
        em.persist(tom);
    }

    private void addMembership(Long userId, ScopeType scopeType, Long scopeId,
                               RoleKind roleKind, LocalDateTime leftAt) {
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .leftAt(leftAt)
                .build();
        em.persist(ms);
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    // ---------------------------------------------------------------------
    // (1) 多段配下解決 + 1 段版との対比（回帰防止）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("多段配下解決_root→中間→末端チームのメンバーまで到達する")
    void 多段配下解決で末端チームまで到達() {
        // org階層: root → mid → leaf（leaf に末端チーム leafTeam を ACTIVE で接続）
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        // 各層の直属メンバー
        Long rootDirect = persistActiveUser();
        grantOrgRole(rootDirect, rootOrg);
        Long midDirect = persistActiveUser();
        grantOrgRole(midDirect, midOrg);
        Long leafDirect = persistActiveUser();
        grantOrgRole(leafDirect, leafOrg);

        // 末端組織の配下参加チーム（ACTIVE）のメンバー
        Long leafTeam = 600_001L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        Long leafTeamMember = persistActiveUser();
        grantTeamRole(leafTeamMember, leafTeam);
        flushClear();

        List<Long> recursive =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(rootOrg, false, MAX_DEPTH);

        // 全子孫組織の直属 ∪ 末端チームメンバーまで到達
        assertThat(recursive)
                .containsExactlyInAnyOrder(rootDirect, midDirect, leafDirect, leafTeamMember);

        // 対比: 1 段版は root 直属のみ（末端チーム/中間/末端組織には到達しない）
        List<Long> oneLevel =
                userRoleRepository.findDistributionUserIdsForOrganization(rootOrg, false);
        assertThat(oneLevel).containsExactly(rootDirect);
        assertThat(oneLevel).doesNotContain(midDirect, leafDirect, leafTeamMember);
    }

    @Test
    @DisplayName("削除済み中間組織のさらに配下は展開されない（CTEのdeleted_atフィルタ）")
    void 削除済み中間組織配下は展開されない() {
        Long rootOrg = persistOrganization(null);
        Long deletedMid = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(deletedMid);

        Long leafDirect = persistActiveUser();
        grantOrgRole(leafDirect, leafOrg);

        // 中間組織を論理削除（CTE の deleted_at IS NULL で枝刈りされる）
        OrganizationEntity mid = em.find(OrganizationEntity.class, deletedMid);
        mid.softDelete();
        em.merge(mid);
        flushClear();

        List<Long> recursive =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(rootOrg, false, MAX_DEPTH);

        // 中間組織が削除されているため、その配下 leaf も枝刈りされ leafDirect は到達しない
        assertThat(recursive).doesNotContain(leafDirect);
    }

    // ---------------------------------------------------------------------
    // (2) サイクル防止
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("サイクル防止_循環するparent_organization_idでも停止する")
    void サイクルでも停止する() {
        // org A ⇄ B（相互に親を指す循環）
        Long orgA = persistOrganization(null);
        Long orgB = persistOrganization(orgA);
        // A の親を B にして循環を作る。
        // toBuilder().build() + em.merge は新インスタンス（同一 slug 'm1-org-N'）を生成し、
        // merge が UPDATE ではなく INSERT に解決されて slug 一意制約違反
        // （Duplicate entry ... for key 'organizations.UKsfr9...'）を起こすため使わない。
        // 親 ID の付け替えだけが目的なので native UPDATE で確実に既存行を更新する。
        em.createNativeQuery(
                        "UPDATE organizations SET parent_organization_id = :p WHERE id = :id")
                .setParameter("p", orgB)
                .setParameter("id", orgA)
                .executeUpdate();
        em.flush();
        em.clear();

        Long memberA = persistActiveUser();
        grantOrgRole(memberA, orgA);
        Long memberB = persistActiveUser();
        grantOrgRole(memberB, orgB);
        flushClear();

        // maxDepth が小さくても無限ループせず停止し、A/B の直属を返す
        List<Long> recursive =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(orgA, false, 5);

        assertThat(recursive).contains(memberA, memberB);
        // maxDepth=32 でも同様に停止すること（タイムアウトせず返る）
        List<Long> recursiveDeep =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(orgA, false, MAX_DEPTH);
        assertThat(recursiveDeep).contains(memberA, memberB);
    }

    // ---------------------------------------------------------------------
    // (3) SUPPORTER 除外の再帰展開
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("多段_純SUPPORTERは除外され_別スコープMEMBER保有者は除外されない")
    void 多段SUPPORTER除外とMEMBER優先() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);

        Long leafTeam = 600_010L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // (a) 末端チームで純SUPPORTER → 除外される
        Long pureSupporter = persistActiveUser();
        grantTeamRole(pureSupporter, leafTeam);
        addMembership(pureSupporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);

        // (b) leaf 組織で SUPPORTER だが root 組織では MEMBER → MEMBER 優先で除外されない
        Long mixed = persistActiveUser();
        grantOrgRole(mixed, rootOrg);
        addMembership(mixed, ScopeType.ORGANIZATION, leafOrg, RoleKind.SUPPORTER, null);
        addMembership(mixed, ScopeType.ORGANIZATION, rootOrg, RoleKind.MEMBER, null);

        // (c) 通常メンバー（membershipsなし）→ 含まれる
        Long plain = persistActiveUser();
        grantOrgRole(plain, leafOrg);
        flushClear();

        List<Long> excluded =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(rootOrg, false, MAX_DEPTH);
        assertThat(excluded).contains(mixed, plain);
        assertThat(excluded).doesNotContain(pureSupporter);

        // includeSupporters=true なら純SUPPORTERも含まれる
        List<Long> included =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(rootOrg, true, MAX_DEPTH);
        assertThat(included).contains(pureSupporter, mixed, plain);
    }

    // ---------------------------------------------------------------------
    // (4) EXISTS 版（isUserInUniverse 用）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("EXISTS版_配下チームのみ所属ユーザーはtrue_無関係ユーザーはfalse")
    void existsで配下チーム所属者true無関係false() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        Long leafTeam = 600_020L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        Long leafTeamMember = persistActiveUser();
        grantTeamRole(leafTeamMember, leafTeam);

        // 配下組織の直属者
        Long leafDirect = persistActiveUser();
        grantOrgRole(leafDirect, leafOrg);

        // 無関係な別組織のメンバー
        Long otherOrg = persistOrganization(null);
        Long outsider = persistActiveUser();
        grantOrgRole(outsider, otherOrg);
        flushClear();

        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, leafTeamMember, MAX_DEPTH))
                .isTrue();
        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, leafDirect, MAX_DEPTH))
                .isTrue();
        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, outsider, MAX_DEPTH))
                .isFalse();
    }

    @Test
    @DisplayName("EXISTS版_SUPPORTERでも所属軸なのでtrue（G7: 配信トグルと別軸）")
    void existsはSUPPORTERでもtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 600_030L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long supporter = persistActiveUser();
        grantTeamRole(supporter, leafTeam);
        addMembership(supporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);
        flushClear();

        // EXISTS 版は SUPPORTER 除外をかけない（所属していれば true）
        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, supporter, MAX_DEPTH))
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // (4b) 応答母集団 EXISTS 版（欠陥Z 根治・純 SUPPORTER 除外版）
    //      existsActiveMemberInOrganizationDescendants
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("応答EXISTS版_配下チームのみ所属MEMBERはtrue_無関係はfalse")
    void 応答EXISTSで配下チームMEMBERはtrue無関係false() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        Long leafTeam = 600_040L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 配下チームのみ所属（memberships なし＝純 SUPPORTER でない通常メンバー）
        Long leafTeamMember = persistActiveUser();
        grantTeamRole(leafTeamMember, leafTeam);

        // 配下組織の直属者
        Long leafDirect = persistActiveUser();
        grantOrgRole(leafDirect, leafOrg);

        // 無関係な別組織のメンバー
        Long otherOrg = persistOrganization(null);
        Long outsider = persistActiveUser();
        grantOrgRole(outsider, otherOrg);
        flushClear();

        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, leafTeamMember, MAX_DEPTH))
                .isTrue();
        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, leafDirect, MAX_DEPTH))
                .isTrue();
        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, outsider, MAX_DEPTH))
                .isFalse();
    }

    @Test
    @DisplayName("応答EXISTS版_配下の純SUPPORTERはfalse（御裁可②: 応答不可）")
    void 応答EXISTSは純SUPPORTERでfalse() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 600_041L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long pureSupporter = persistActiveUser();
        grantTeamRole(pureSupporter, leafTeam);
        addMembership(pureSupporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);
        flushClear();

        // 所属軸 EXISTS は true（可視性向け）
        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, pureSupporter, MAX_DEPTH))
                .isTrue();
        // 応答母集団 EXISTS は純 SUPPORTER を除外して false（回答可否向け）
        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, pureSupporter, MAX_DEPTH))
                .isFalse();
    }

    @Test
    @DisplayName("応答EXISTS版_別スコープでMEMBERを持つSUPPORTERはMEMBER優先でtrue")
    void 応答EXISTSはMEMBER優先でtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 600_042L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // leaf 組織で SUPPORTER だが root 組織では MEMBER → MEMBER 優先で true
        Long mixed = persistActiveUser();
        grantOrgRole(mixed, rootOrg);
        addMembership(mixed, ScopeType.ORGANIZATION, leafOrg, RoleKind.SUPPORTER, null);
        addMembership(mixed, ScopeType.ORGANIZATION, rootOrg, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, mixed, MAX_DEPTH))
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // (5) バルク版（フェーズM2 / ORGANIZATION_AND_DESCENDANTS 可視性判定の土台）
    //     findOrgRootsWhereUserIsDescendantMember: 複数 ORG 根 × 単一 viewer を 1 クエリ
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("バルク版_viewerが配下に属する根ORGのみが返る（複数根を1クエリで判定）")
    void バルク版_配下に属する根のみ返る() {
        // 根A: 配下に viewer の所属あり（孫組織配下チームのみ所属）
        Long rootA = persistOrganization(null);
        Long midA = persistOrganization(rootA);
        Long leafA = persistOrganization(midA);
        Long leafTeamA = 600_100L;
        linkTeamToOrg(leafTeamA, leafA, TeamOrgMembershipEntity.Status.ACTIVE);

        // 根B: viewer は B 配下に一切所属しない
        Long rootB = persistOrganization(null);
        Long leafB = persistOrganization(rootB);

        // 根C: viewer は C の直属（直接所属）
        Long rootC = persistOrganization(null);

        Long viewer = persistActiveUser();
        grantTeamRole(viewer, leafTeamA);   // A の孫組織配下チームのみ所属
        grantOrgRole(viewer, rootC);        // C の直属
        flushClear();

        List<Long> matched = userRoleRepository.findOrgRootsWhereUserIsDescendantMember(
                java.util.Set.of(rootA, rootB, rootC), viewer, MAX_DEPTH);

        // A（配下チーム所属）と C（直属）は返り、B は返らない
        assertThat(matched).containsExactlyInAnyOrder(rootA, rootC);
        assertThat(matched).doesNotContain(rootB, leafA, leafB);
    }

    @Test
    @DisplayName("バルク版_SUPPORTERでも所属軸なので返る（G7・配信トグルと別軸）")
    void バルク版_SUPPORTERでも返る() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 600_110L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long supporter = persistActiveUser();
        grantTeamRole(supporter, leafTeam);
        addMembership(supporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);
        flushClear();

        List<Long> matched = userRoleRepository.findOrgRootsWhereUserIsDescendantMember(
                java.util.Set.of(rootOrg), supporter, MAX_DEPTH);

        assertThat(matched).containsExactly(rootOrg);
    }

    @Test
    @DisplayName("バルク版_削除済み中間組織のさらに配下は枝刈りされ根に算入されない")
    void バルク版_削除済み中間配下は枝刈り() {
        Long rootOrg = persistOrganization(null);
        Long deletedMid = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(deletedMid);

        Long leafTeam = 600_120L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        Long leafTeamMember = persistActiveUser();
        grantTeamRole(leafTeamMember, leafTeam);

        OrganizationEntity mid = em.find(OrganizationEntity.class, deletedMid);
        mid.softDelete();
        em.merge(mid);
        flushClear();

        // 中間組織が削除 → その配下 leaf も枝刈り → leafTeamMember は root の配下と見なされない
        List<Long> matched = userRoleRepository.findOrgRootsWhereUserIsDescendantMember(
                java.util.Set.of(rootOrg), leafTeamMember, MAX_DEPTH);
        assertThat(matched).isEmpty();
    }
}
