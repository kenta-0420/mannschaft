package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2780 甲層: 組織配下の所属判定が {@code memberships} 専属メンバーを取りこぼす欠陥の
 * 受け入れテスト（試練 = テスト先行）。
 *
 * <p>{@code V60.010} で MEMBER / SUPPORTER の在籍行は {@code user_roles} から
 * {@code memberships} へ完全移行済みであるが、下記の候補集合は依然 {@code user_roles} のみを
 * 走査するため、{@code memberships} にしか在籍行を持たない一般メンバーが
 * 「組織配下に属していない」と判定される。</p>
 *
 * <ul>
 *   <li>{@link UserRoleRepository#existsUserInOrganizationDescendants(Long, Long, int)}</li>
 *   <li>{@link UserRoleRepository#existsInOrgDistributionAudience(Long, Long, boolean, int)}</li>
 *   <li>{@link UserRoleRepository#existsActiveMemberInOrganizationDescendants(Long, Long, int)}</li>
 *   <li>{@link UserRoleRepository#findDescendantMembershipRolesByOrgRoots(Set, Long, int)}</li>
 * </ul>
 *
 * <p>本クラスは実 MySQL（Testcontainers）に対して 2 系統（{@code user_roles} /
 * {@code memberships}）の在籍行を実際に永続化して検証する。snapshot をスタブした
 * ユニットテストでは 2 系統の非対称を再現できず、green のまま本番だけ壊れるため
 * モック・スタブでの再現は行わない。</p>
 *
 * <p><b>陽性対照を同居させている</b>（AC-6 / AC-7 / AC-8 / AC-9）。{@code memberships} へ
 * 候補集合を広げる過程で、従来 {@code user_roles} 由来で拾えていた役職者を落とす
 * 逆向きの回帰・退会者や非アクティブユーザーの混入が起きないことを同時に締める。</p>
 */
@Transactional
@DisplayName("Issue #2780 甲層: 組織配下の所属判定における memberships 専属メンバーの取りこぼし")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleOrgDescendantsMembershipsAudienceTest extends AbstractMySqlIntegrationTest {

    private static final int MAX_DEPTH = 32;

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 本テスト専用のチーム ID 採番（team_org_memberships のみ参照するため teams 行は不要）。 */
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipBatchQueryService membershipBatchQueryService;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー（UserRoleDistributionRecursiveRepositoryTest の金型を踏襲）
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long nextTeamId() {
        return 610_000L + TEAM_SEQ.incrementAndGet();
    }

    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("i2780-org-" + n)
                .name("2780テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        return org.getId();
    }

    private Long persistUser(UserEntity.UserStatus status) {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("i2780-user-" + n + "@example.com")
                .lastName("所属")
                .firstName("判定" + n)
                .displayName("所属判定" + n)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private Long persistActiveUser() {
        return persistUser(UserEntity.UserStatus.ACTIVE);
    }

    /**
     * 指定名のロールを取得（無ければ作成）する。
     * test profile は Flyway 無効で {@code roles} が空表のため、必要なロール行は自前で用意する。
     */
    private Long persistRoleIfNeeded(String name, int priority) {
        List<?> found = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getResultList();
        if (!found.isEmpty()) {
            return ((Number) found.get(0)).longValue();
        }
        RoleEntity role = RoleEntity.builder()
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem(true)
                .build();
        em.persist(role);
        em.flush();
        return role.getId();
    }

    private void grantOrgRole(Long userId, Long organizationId, String roleName, int priority) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded(roleName, priority))
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void grantTeamRole(Long userId, Long teamId, String roleName, int priority) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded(roleName, priority))
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void linkTeamToOrg(Long teamId, Long organizationId) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
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

    /** ユーザーを論理削除する（{@code deleted_at} を立てる）。 */
    private void softDeleteUser(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /** {@code findDescendantMembershipRolesByOrgRoots} が返したロール名の一覧。 */
    private List<String> descendantRoleNames(Long rootOrgId, Long userId) {
        return userRoleRepository.findDescendantMembershipRolesByOrgRoots(
                        Set.of(rootOrgId), userId, MAX_DEPTH).stream()
                .map(UserRoleRepository.DescendantMembershipRoleProjection::getRoleName)
                .toList();
    }

    // =====================================================================
    // AC-1 / AC-2: 所属軸 EXISTS が memberships 専属メンバーを拾う
    // =====================================================================

    /**
     * AC-1: 組織に {@code memberships} のみで在籍する一般メンバーが
     * {@code existsUserInOrganizationDescendants} で true になること。
     */
    @Test
    @DisplayName("AC-1: 組織にmemberships専属で在籍する一般メンバーは所属軸EXISTSでtrue")
    void ac1_組織memberships専属メンバーは所属軸EXISTSでtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);

        // user_roles 行を一切持たず、memberships にのみ在籍する一般メンバー（V60.010 後の正常な姿）
        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, membershipsOnly, MAX_DEPTH))
                .as("memberships にのみ在籍行を持つ配下組織の一般メンバーは配下ツリーの所属者である")
                .isTrue();
    }

    /**
     * AC-2: 配下チーム（{@code team_org_memberships} で組織に紐づく）に
     * {@code memberships} のみで在籍する者も true になること（チーム経路）。
     */
    @Test
    @DisplayName("AC-2: 配下チームにmemberships専属で在籍する一般メンバーも所属軸EXISTSでtrue")
    void ac2_配下チームmemberships専属メンバーも所属軸EXISTSでtrue() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);

        // 無関係な別組織配下チームの memberships 専属メンバー（母集団の広がりすぎを同時に締める）
        Long otherOrg = persistOrganization(null);
        Long otherTeam = nextTeamId();
        linkTeamToOrg(otherTeam, otherOrg);
        Long outsider = persistActiveUser();
        addMembership(outsider, ScopeType.TEAM, otherTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, membershipsOnly, MAX_DEPTH))
                .as("配下 ACTIVE チームに memberships で在籍する一般メンバーも配下ツリーの所属者である")
                .isTrue();
        assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, outsider, MAX_DEPTH))
                .as("別ツリーの memberships 在籍者を巻き込んではならない")
                .isFalse();
    }

    /**
     * AC-1 相当（応答母集団版）: {@code existsActiveMemberInOrganizationDescendants} も
     * memberships 専属の一般メンバーを拾うこと（純 SUPPORTER 除外規約は維持）。
     */
    @Test
    @DisplayName("AC-1相当: 応答母集団EXISTSもmemberships専属の一般メンバーでtrue（純SUPPORTERは除外を維持）")
    void ac1相当_応答母集団EXISTSもmemberships専属メンバーでtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long membershipsOnlyMember = persistActiveUser();
        addMembership(membershipsOnlyMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);

        Long membershipsOnlySupporter = persistActiveUser();
        addMembership(membershipsOnlySupporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);
        flushClear();

        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(
                rootOrg, membershipsOnlyMember, MAX_DEPTH))
                .as("memberships 専属の一般メンバーは組織発コンテンツの応答母集団に含まれる")
                .isTrue();
        assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(
                rootOrg, membershipsOnlySupporter, MAX_DEPTH))
                .as("純 SUPPORTER は応答母集団から除外されるという既存規約は維持する")
                .isFalse();
    }

    // =====================================================================
    // AC-3: 配信母集団 EXISTS
    // =====================================================================

    /**
     * AC-3: {@code existsInOrgDistributionAudience} が memberships 専属の
     * 一般メンバーに対し（トグル OFF / ON いずれでも）true を返すこと。
     */
    @Test
    @DisplayName("AC-3: 配信母集団EXISTSもmemberships専属の一般メンバーでtrue（トグルOFF/ONとも）")
    void ac3_配信母集団EXISTSもmemberships専属メンバーでtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long orgMembershipsOnly = persistActiveUser();
        addMembership(orgMembershipsOnly, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);

        Long teamMembershipsOnly = persistActiveUser();
        addMembership(teamMembershipsOnly, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        for (boolean includeSupporters : new boolean[]{false, true}) {
            assertThat(userRoleRepository.existsInOrgDistributionAudience(
                    rootOrg, orgMembershipsOnly, includeSupporters, MAX_DEPTH))
                    .as("配下組織に memberships で在籍する一般メンバーは配信母集団（トグル=%s）に含まれる",
                            includeSupporters)
                    .isTrue();
            assertThat(userRoleRepository.existsInOrgDistributionAudience(
                    rootOrg, teamMembershipsOnly, includeSupporters, MAX_DEPTH))
                    .as("配下チームに memberships で在籍する一般メンバーは配信母集団（トグル=%s）に含まれる",
                            includeSupporters)
                    .isTrue();
        }
    }

    // =====================================================================
    // AC-4 / AC-5: バルク版のロール名供給
    // =====================================================================

    /**
     * AC-4: {@code findDescendantMembershipRolesByOrgRoots} が memberships 専属メンバーに対し
     * {@code roleName="MEMBER"} を返すこと。
     *
     * <p>候補集合に足すだけでは不十分である。{@code UserScopeRoleSnapshot#hasDescendantRoleOrAbove}
     * はロール名が無い場合 fail-closed で false を返すため、ロール名も同時に供給されなければ
     * 閲覧閾値の評価段で再び落ちる。</p>
     */
    @Test
    @DisplayName("AC-4: バルク版はmemberships専属メンバーにroleName=MEMBERを返す")
    void ac4_バルク版はmemberships専属メンバーにMEMBERを返す() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        List<UserRoleRepository.DescendantMembershipRoleProjection> rows =
                userRoleRepository.findDescendantMembershipRolesByOrgRoots(
                        Set.of(rootOrg), membershipsOnly, MAX_DEPTH);

        assertThat(rows)
                .as("memberships 専属メンバーは根 ORG の配下所属として 1 行返るべきである")
                .hasSize(1);
        assertThat(rows.get(0).getRootOrgId()).isEqualTo(rootOrg);
        assertThat(rows.get(0).getRoleName())
                .as("ロール名が無いと閲覧閾値の評価が fail-closed で false になり修正が無効化される")
                .isEqualTo("MEMBER");
    }

    /**
     * AC-5: SUPPORTER として memberships に在籍する者は
     * {@code roleName="SUPPORTER"} を返すこと（所属軸なので行自体は返る）。
     */
    @Test
    @DisplayName("AC-5: バルク版はmemberships専属のSUPPORTERにroleName=SUPPORTERを返す")
    void ac5_バルク版はmemberships専属SUPPORTERにSUPPORTERを返す() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long supporter = persistActiveUser();
        addMembership(supporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);
        flushClear();

        List<UserRoleRepository.DescendantMembershipRoleProjection> rows =
                userRoleRepository.findDescendantMembershipRolesByOrgRoots(
                        Set.of(rootOrg), supporter, MAX_DEPTH);

        assertThat(rows)
                .as("所属軸のバルク版は SUPPORTER も配下所属として返す（G7: 配信トグルとは別軸）")
                .hasSize(1);
        assertThat(rows.get(0).getRoleName()).isEqualTo("SUPPORTER");
    }

    // =====================================================================
    // AC-6 / AC-7: 陽性対照（逆向き回帰・重複の検出）
    // =====================================================================

    /**
     * AC-6【陽性対照】: {@code user_roles} に ADMIN 行のみを持つ役職者が
     * AC-1〜4 のすべてで従来どおり true になること。
     *
     * <p>{@code memberships} へ候補集合を寄せる過程で、{@code user_roles} 由来の
     * 役職者を落とす逆向きの回帰が起きないことを締める番人である。</p>
     */
    @Test
    @DisplayName("AC-6【陽性対照】: user_rolesにADMIN行のみを持つ役職者は4本すべてで従来どおりtrue")
    void ac6_陽性対照_userRoles専属のADMIN役職者は全経路でtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        // 役職者は memberships を持たず user_roles の ADMIN 行のみを持つ（V60.010 後もこの姿）
        Long orgAdmin = persistActiveUser();
        grantOrgRole(orgAdmin, leafOrg, "ADMIN", 2);
        Long teamAdmin = persistActiveUser();
        grantTeamRole(teamAdmin, leafTeam, "ADMIN", 2);
        flushClear();

        for (Long admin : List.of(orgAdmin, teamAdmin)) {
            assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, admin, MAX_DEPTH))
                    .as("user_roles 由来の役職者は所属軸 EXISTS で従来どおり true")
                    .isTrue();
            assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, admin, MAX_DEPTH))
                    .as("user_roles 由来の役職者は応答母集団 EXISTS で従来どおり true")
                    .isTrue();
            assertThat(userRoleRepository.existsInOrgDistributionAudience(rootOrg, admin, false, MAX_DEPTH))
                    .as("user_roles 由来の役職者は配信母集団 EXISTS で従来どおり true")
                    .isTrue();
            assertThat(descendantRoleNames(rootOrg, admin))
                    .as("user_roles 由来の役職者はバルク版で ADMIN のロール名を保持する")
                    .containsExactly("ADMIN");
        }
    }

    /**
     * AC-7【陽性対照】: 両系統（{@code user_roles} と {@code memberships}）に行を持つ者が
     * 重複せず 1 件として扱われること。
     *
     * <p>2 系統を UNION する際に DISTINCT を落とすと、同一ユーザーが複数行として返り
     * 集計・ロール名解決が二重になる。</p>
     */
    @Test
    @DisplayName("AC-7【陽性対照】: 両系統に行を持つ者はバルク版で重複せず1件として扱われる")
    void ac7_陽性対照_両系統保有者は重複しない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        // 同一スコープに user_roles の MEMBER 行と memberships の MEMBER 行の両方を持つ
        Long dual = persistActiveUser();
        grantTeamRole(dual, leafTeam, "MEMBER", 4);
        addMembership(dual, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        List<UserRoleRepository.DescendantMembershipRoleProjection> rows =
                userRoleRepository.findDescendantMembershipRolesByOrgRoots(
                        Set.of(rootOrg), dual, MAX_DEPTH);

        assertThat(rows)
                .as("両系統に在籍行があっても (根 ORG, ロール名) は 1 件に畳まれるべきである")
                .hasSize(1);
        assertThat(rows.get(0).getRootOrgId()).isEqualTo(rootOrg);
        assertThat(rows.get(0).getRoleName()).isEqualTo("MEMBER");
    }

    // =====================================================================
    // AC-8 / AC-9: 母集団を広げすぎない番人
    // =====================================================================

    /**
     * AC-8: {@code left_at IS NOT NULL}（退会済）の membership は候補に含まれないこと。
     */
    @Test
    @DisplayName("AC-8: 退会済（left_at非NULL）のmembershipは4本すべてで含まれない")
    void ac8_退会済membershipは含まれない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long leftOrgMember = persistActiveUser();
        addMembership(leftOrgMember, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER,
                LocalDateTime.now().minusDays(1));
        Long leftTeamMember = persistActiveUser();
        addMembership(leftTeamMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER,
                LocalDateTime.now().minusDays(1));
        flushClear();

        for (Long left : List.of(leftOrgMember, leftTeamMember)) {
            assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, left, MAX_DEPTH))
                    .as("退会済 membership しか持たない者は所属軸 EXISTS に含まれない")
                    .isFalse();
            assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, left, MAX_DEPTH))
                    .as("退会済 membership しか持たない者は応答母集団に含まれない")
                    .isFalse();
            assertThat(userRoleRepository.existsInOrgDistributionAudience(rootOrg, left, true, MAX_DEPTH))
                    .as("退会済 membership しか持たない者は配信母集団に含まれない")
                    .isFalse();
            assertThat(descendantRoleNames(rootOrg, left))
                    .as("退会済 membership しか持たない者はバルク版で 1 行も返らない")
                    .isEmpty();
        }
    }

    /**
     * AC-9: {@code users.deleted_at IS NOT NULL} / {@code status != 'ACTIVE'} のユーザーは
     * 両系統とも候補に含まれないこと。
     */
    @Test
    @DisplayName("AC-9: 論理削除済・非ACTIVEユーザーは両系統とも4本すべてで含まれない")
    void ac9_論理削除済と非ACTIVEは両系統とも含まれない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        // memberships 系統: 論理削除済 / 非 ACTIVE
        Long deletedViaMemberships = persistActiveUser();
        addMembership(deletedViaMemberships, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        Long frozenViaMemberships = persistUser(UserEntity.UserStatus.FROZEN);
        addMembership(frozenViaMemberships, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);

        // user_roles 系統: 論理削除済 / 非 ACTIVE
        Long deletedViaUserRoles = persistActiveUser();
        grantTeamRole(deletedViaUserRoles, leafTeam, "ADMIN", 2);
        Long frozenViaUserRoles = persistUser(UserEntity.UserStatus.FROZEN);
        grantOrgRole(frozenViaUserRoles, leafOrg, "ADMIN", 2);

        em.flush();
        softDeleteUser(deletedViaMemberships);
        softDeleteUser(deletedViaUserRoles);
        flushClear();

        List<Long> excluded = List.of(
                deletedViaMemberships, frozenViaMemberships, deletedViaUserRoles, frozenViaUserRoles);
        for (Long userId : excluded) {
            assertThat(userRoleRepository.existsUserInOrganizationDescendants(rootOrg, userId, MAX_DEPTH))
                    .as("論理削除済 / 非 ACTIVE ユーザーは所属軸 EXISTS に含まれない (userId=%s)", userId)
                    .isFalse();
            assertThat(userRoleRepository.existsActiveMemberInOrganizationDescendants(rootOrg, userId, MAX_DEPTH))
                    .as("論理削除済 / 非 ACTIVE ユーザーは応答母集団に含まれない (userId=%s)", userId)
                    .isFalse();
            assertThat(userRoleRepository.existsInOrgDistributionAudience(rootOrg, userId, true, MAX_DEPTH))
                    .as("論理削除済 / 非 ACTIVE ユーザーは配信母集団に含まれない (userId=%s)", userId)
                    .isFalse();
            assertThat(descendantRoleNames(rootOrg, userId))
                    .as("論理削除済 / 非 ACTIVE ユーザーはバルク版で 1 行も返らない (userId=%s)", userId)
                    .isEmpty();
        }
    }

    // =====================================================================
    // AC-10 / AC-21: F00 snapshot 経由（消費側まで通す）
    // =====================================================================

    /**
     * AC-10: F00 snapshot 経由で {@code isDescendantMemberOf} /
     * {@code hasDescendantRoleOrAbove} が memberships 専属メンバーに true を返すこと。
     *
     * <p>Repository を直接叩くだけでは消費側（{@code AbstractContentVisibilityResolver} /
     * {@code ScheduleVisibilityResolver}）まで届いた保証にならないため、
     * {@code MembershipBatchQueryService#snapshotForUser} を実 DB で通して検証する。</p>
     */
    @Test
    @DisplayName("AC-10: F00 snapshot経由でmemberships専属メンバーが配下所属・閲覧閾値ともtrue")
    void ac10_snapshot経由でmemberships専属メンバーが配下所属と閲覧閾値でtrue() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        ScopeKey rootScope = new ScopeKey("ORGANIZATION", rootOrg);
        UserScopeRoleSnapshot snapshot = membershipBatchQueryService.snapshotForUser(
                membershipsOnly, Set.of(), Set.of(), Set.of(rootScope));

        assertThat(snapshot.isSystemAdmin())
                .as("陽性対照が SystemAdmin で誤って true になっていないこと")
                .isFalse();
        assertThat(snapshot.isDescendantMemberOf(rootScope))
                .as("memberships 専属メンバーは ORGANIZATION_AND_DESCENDANTS の配下所属者である")
                .isTrue();
        assertThat(snapshot.hasDescendantRoleOrAbove(rootScope, "MEMBER"))
                .as("ロール名が供給されないと閲覧閾値が fail-closed で false になる")
                .isTrue();
    }

    /**
     * AC-21: snapshot 構築の SQL 本数が上限（7 本）を超えないこと。
     *
     * <p>下向き再帰の候補集合を 2 系統へ広げる際、素朴にクエリを 1 本足すと
     * この上限に抵触する。既存の {@code MembershipBatchQueryServiceIntegrationTest} は
     * direct スコープのみのシナリオ（≦4）しか締めていないため、
     * {@code descendantScopes} を伴う経路の番人を本テストで置く。</p>
     */
    @Test
    @DisplayName("AC-21: descendantScopesを伴うsnapshot構築のSQL本数は7本以下")
    void ac21_snapshot構築のSQL本数は7本以下() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = nextTeamId();
        linkTeamToOrg(leafTeam, leafOrg);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        membershipBatchQueryService.snapshotForUser(
                membershipsOnly, Set.of(), Set.of(), Set.of(new ScopeKey("ORGANIZATION", rootOrg)));

        assertThat(stats.getPrepareStatementCount())
                .as("descendantScopes を伴う snapshot 構築の SQL 本数は 7 本以下であるべし")
                .isLessThanOrEqualTo(7L);
    }
}
