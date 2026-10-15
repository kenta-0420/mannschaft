package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
 * CMP-050: 在籍プリミティブ 4 本が非 ACTIVE ユーザーを所属扱いする欠陥の受け入れテスト（試練 = テスト先行）。
 *
 * <p>{@link UserRoleRepository} の列挙系クエリ（{@code findUserIdsByScope} 等）は一律で
 * {@code JOIN users u ... AND u.deleted_at IS NULL AND u.status = 'ACTIVE'} を課しているのに対し、
 * 在籍軸のプリミティブ 4 本（{@code countRoleOrMembershipByUserIdAndTeamId} /
 * {@code countRoleOrMembershipByUserIdAndOrganizationId} / {@code findOrganizationIdsByUserId} /
 * {@code findTeamIdsByUserId}）は {@code users} を一切見ていない。この非対称により、凍結・論理削除済み
 * ユーザーが「在籍している」と判定され、{@code RoleService#transferOwnership} が唯一の ADMIN を
 * 凍結ユーザーへ譲渡できてしまう（＝スコープが操作不能になる）。</p>
 *
 * <p>本テストは実 MySQL（Testcontainers）に 2 系統（{@code user_roles} / {@code memberships}）の
 * 在籍行を実際に永続化して検証する。native query の非対称はモック・スタブでは再現不能であり、
 * スタブしたユニットテストは green のまま本番だけ壊れるため用いない。</p>
 *
 * <p>同時に、状態を問わない家族経路専用の在籍判定
 * {@link UserRoleRepository#existsAnyStatusByUserIdAndTeamId(Long, Long)} が
 * 「状態は問わないが離脱（{@code left_at}）は問う」ことも締める。</p>
 */
@Transactional
@DisplayName("CMP-050: 在籍プリミティブ4本が非ACTIVEユーザーを所属扱いしない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleFlatMembershipActiveGuardTest extends AbstractMySqlIntegrationTest {

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 本テスト専用のチーム ID 採番（在籍判定はスコープ ID のみ参照するため teams 行は不要）。 */
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー（UserRolePeripheralScopeMembershipsTest の金型を踏襲）
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long nextTeamId() {
        return 50_000L + TEAM_SEQ.incrementAndGet();
    }

    private Long persistOrganization() {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("cmp050-org-" + n)
                .name("CMP050テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(null)
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
                .email("cmp050-" + n + "@example.com")
                .lastName("在籍")
                .firstName("判定" + n)
                .displayName("在籍判定" + n)
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

    private void grantTeamRole(Long userId, Long teamId) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded("ADMIN", 2))
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void grantOrgRole(Long userId, Long organizationId) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded("ADMIN", 2))
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void addMembership(Long userId, ScopeType scopeType, Long scopeId, LocalDateTime leftAt) {
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .leftAt(leftAt)
                .build();
        em.persist(ms);
    }

    /**
     * ユーザーを論理削除する。
     *
     * <p>{@link UserEntity} は {@code @SQLRestriction("deleted_at IS NULL")} を持つため、
     * JPA 経由で {@code deletedAt} を立てると以後エンティティが見えなくなる。
     * {@code flush} 後に native の {@code UPDATE} で直接更新し、1 次キャッシュを捨てる。</p>
     */
    private void softDeleteUser(Long userId) {
        em.flush();
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.clear();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-1 / AC-2: existsByUserIdAndTeamId
    // =====================================================================

    /** AC-1: {@code user_roles} に ADMIN 行を持つ FROZEN ユーザーはチーム在籍者ではない。 */
    @Test
    @DisplayName("AC-1: user_rolesにADMIN行を持つFROZENユーザーはexistsByUserIdAndTeamIdがfalse")
    void ac1_userRoles由来のFROZENユーザーはチーム非所属() {
        Long teamId = nextTeamId();
        Long frozen = persistUser(UserEntity.UserStatus.FROZEN);
        grantTeamRole(frozen, teamId);
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(frozen, teamId))
                .as("凍結ユーザーは user_roles に ADMIN 行が残っていてもチーム在籍者として扱ってはならない")
                .isFalse();
    }

    /** AC-2: {@code memberships} 専属（{@code left_at IS NULL}）の FROZEN ユーザーも在籍者ではない。 */
    @Test
    @DisplayName("AC-2: memberships専属のFROZENユーザーはexistsByUserIdAndTeamIdがfalse")
    void ac2_memberships由来のFROZENユーザーはチーム非所属() {
        Long teamId = nextTeamId();
        Long frozen = persistUser(UserEntity.UserStatus.FROZEN);
        addMembership(frozen, ScopeType.TEAM, teamId, null);
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(frozen, teamId))
                .as("凍結ユーザーは memberships に在籍行が残っていてもチーム在籍者として扱ってはならない")
                .isFalse();
    }

    // =====================================================================
    // AC-3: existsByUserIdAndOrganizationId
    // =====================================================================

    /** AC-3: 組織版も両枝それぞれで FROZEN ユーザーを非所属とすること。 */
    @Test
    @DisplayName("AC-3: FROZENユーザーはexistsByUserIdAndOrganizationIdがfalse（user_roles枝・memberships枝の個別検証）")
    void ac3_FROZENユーザーは組織非所属() {
        Long orgViaUserRoles = persistOrganization();
        Long orgViaMemberships = persistOrganization();

        Long frozenWithRole = persistUser(UserEntity.UserStatus.FROZEN);
        grantOrgRole(frozenWithRole, orgViaUserRoles);

        Long frozenWithMembership = persistUser(UserEntity.UserStatus.FROZEN);
        addMembership(frozenWithMembership, ScopeType.ORGANIZATION, orgViaMemberships, null);
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(frozenWithRole, orgViaUserRoles))
                .as("user_roles 枝: 凍結ユーザーを組織在籍者として扱ってはならない")
                .isFalse();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(frozenWithMembership, orgViaMemberships))
                .as("memberships 枝: 凍結ユーザーを組織在籍者として扱ってはならない")
                .isFalse();
    }

    // =====================================================================
    // AC-4 / AC-5: 列挙プリミティブ
    // =====================================================================

    /** AC-4: 両枝に在籍行を持つ FROZEN ユーザーでも {@code findTeamIdsByUserId} は空。 */
    @Test
    @DisplayName("AC-4: FROZENユーザーはfindTeamIdsByUserIdが空リスト（両枝に行があっても）")
    void ac4_FROZENユーザーの所属チームIDは空() {
        Long teamViaRole = nextTeamId();
        Long teamViaMembership = nextTeamId();
        Long frozen = persistUser(UserEntity.UserStatus.FROZEN);
        grantTeamRole(frozen, teamViaRole);
        addMembership(frozen, ScopeType.TEAM, teamViaMembership, null);
        flushClear();

        assertThat(userRoleRepository.findTeamIdsByUserId(frozen))
                .as("凍結ユーザーの所属チームは両系統ともに解決されてはならない")
                .isEmpty();
    }

    /** AC-5: 両枝に在籍行を持つ FROZEN ユーザーでも {@code findOrganizationIdsByUserId} は空。 */
    @Test
    @DisplayName("AC-5: FROZENユーザーはfindOrganizationIdsByUserIdが空リスト（両枝に行があっても）")
    void ac5_FROZENユーザーの所属組織IDは空() {
        Long orgViaRole = persistOrganization();
        Long orgViaMembership = persistOrganization();
        Long frozen = persistUser(UserEntity.UserStatus.FROZEN);
        grantOrgRole(frozen, orgViaRole);
        addMembership(frozen, ScopeType.ORGANIZATION, orgViaMembership, null);
        flushClear();

        assertThat(userRoleRepository.findOrganizationIdsByUserId(frozen))
                .as("凍結ユーザーの所属組織は両系統ともに解決されてはならない")
                .isEmpty();
    }

    // =====================================================================
    // AC-6 / AC-7: 陽性対照（ACTIVE ユーザーの非退行）
    // =====================================================================

    /**
     * AC-6【陽性対照】: ACTIVE ユーザーは 4 メソッドすべてで従来どおり所属を返すこと。
     *
     * <p>{@code user_roles} 専属・{@code memberships} 専属・両方持ちの 3 パターンを個別に締める。
     * {@code users} 結合の追加が在籍者を落とす逆向きの回帰を防ぐ番人である。</p>
     */
    @Test
    @DisplayName("AC-6【陽性対照】: ACTIVEユーザーは4メソッドが従来どおり所属を返す（user_roles専属/memberships専属/両方持ち）")
    void ac6_陽性対照_ACTIVEユーザーは従来どおり所属を返す() {
        // user_roles 専属
        Long teamRoleOnly = nextTeamId();
        Long orgRoleOnly = persistOrganization();
        Long roleOnlyUser = persistActiveUser();
        grantTeamRole(roleOnlyUser, teamRoleOnly);
        grantOrgRole(roleOnlyUser, orgRoleOnly);

        // memberships 専属
        Long teamMsOnly = nextTeamId();
        Long orgMsOnly = persistOrganization();
        Long msOnlyUser = persistActiveUser();
        addMembership(msOnlyUser, ScopeType.TEAM, teamMsOnly, null);
        addMembership(msOnlyUser, ScopeType.ORGANIZATION, orgMsOnly, null);

        // 両方持ち
        Long teamBoth = nextTeamId();
        Long orgBoth = persistOrganization();
        Long dualUser = persistActiveUser();
        grantTeamRole(dualUser, teamBoth);
        addMembership(dualUser, ScopeType.TEAM, teamBoth, null);
        grantOrgRole(dualUser, orgBoth);
        addMembership(dualUser, ScopeType.ORGANIZATION, orgBoth, null);
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(roleOnlyUser, teamRoleOnly))
                .as("user_roles 専属の ACTIVE ユーザーはチーム在籍者である")
                .isTrue();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(roleOnlyUser, orgRoleOnly)).isTrue();
        assertThat(userRoleRepository.findTeamIdsByUserId(roleOnlyUser)).containsExactly(teamRoleOnly);
        assertThat(userRoleRepository.findOrganizationIdsByUserId(roleOnlyUser)).containsExactly(orgRoleOnly);

        assertThat(userRoleRepository.existsByUserIdAndTeamId(msOnlyUser, teamMsOnly))
                .as("memberships 専属の ACTIVE ユーザーはチーム在籍者である")
                .isTrue();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(msOnlyUser, orgMsOnly)).isTrue();
        assertThat(userRoleRepository.findTeamIdsByUserId(msOnlyUser)).containsExactly(teamMsOnly);
        assertThat(userRoleRepository.findOrganizationIdsByUserId(msOnlyUser)).containsExactly(orgMsOnly);

        assertThat(userRoleRepository.existsByUserIdAndTeamId(dualUser, teamBoth))
                .as("両系統に在籍行を持つ ACTIVE ユーザーはチーム在籍者である")
                .isTrue();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(dualUser, orgBoth)).isTrue();
    }

    /** AC-7: 両系統に在籍行を持つ ACTIVE ユーザーで UNION の重複排除が維持されること。 */
    @Test
    @DisplayName("AC-7: 両系統保有のACTIVEユーザーでfindTeamIdsByUserIdに同一team_idが1件だけ")
    void ac7_両系統保有でも所属チームIDは重複しない() {
        Long teamId = nextTeamId();
        Long orgId = persistOrganization();
        Long dual = persistActiveUser();
        grantTeamRole(dual, teamId);
        addMembership(dual, ScopeType.TEAM, teamId, null);
        grantOrgRole(dual, orgId);
        addMembership(dual, ScopeType.ORGANIZATION, orgId, null);
        flushClear();

        assertThat(userRoleRepository.findTeamIdsByUserId(dual))
                .as("UNION の重複排除により同一 team_id は 1 件に畳まれるべきである")
                .containsExactly(teamId);
        assertThat(userRoleRepository.findOrganizationIdsByUserId(dual))
                .as("組織側も同様に 1 件へ畳まれるべきである")
                .containsExactly(orgId);
    }

    // =====================================================================
    // AC-8 / AC-9: 論理削除・ACTIVE 以外の status 一般
    // =====================================================================

    /** AC-8: 論理削除済み（status は ACTIVE のまま）で 4 メソッドすべて非所属。 */
    @Test
    @DisplayName("AC-8: 論理削除済み（deleted_at非NULL・statusはACTIVE）で4メソッドすべて非所属")
    void ac8_論理削除済みユーザーは4メソッドすべて非所属() {
        Long teamId = nextTeamId();
        Long orgId = persistOrganization();
        Long deleted = persistActiveUser();
        grantTeamRole(deleted, teamId);
        addMembership(deleted, ScopeType.TEAM, teamId, null);
        grantOrgRole(deleted, orgId);
        addMembership(deleted, ScopeType.ORGANIZATION, orgId, null);
        softDeleteUser(deleted);
        em.flush();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(deleted, teamId))
                .as("論理削除済みユーザーをチーム在籍者として扱ってはならない")
                .isFalse();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(deleted, orgId))
                .as("論理削除済みユーザーを組織在籍者として扱ってはならない")
                .isFalse();
        assertThat(userRoleRepository.findTeamIdsByUserId(deleted)).isEmpty();
        assertThat(userRoleRepository.findOrganizationIdsByUserId(deleted)).isEmpty();
    }

    /** AC-9: ACTIVE 以外の status 一般（非 FROZEN の例として ARCHIVED）でも 4 メソッドすべて非所属。 */
    @Test
    @DisplayName("AC-9: ARCHIVED（非FROZEN・非ACTIVE）ユーザーも4メソッドすべて非所属")
    void ac9_ARCHIVEDユーザーも4メソッドすべて非所属() {
        Long teamId = nextTeamId();
        Long orgId = persistOrganization();
        Long archived = persistUser(UserEntity.UserStatus.ARCHIVED);
        grantTeamRole(archived, teamId);
        addMembership(archived, ScopeType.TEAM, teamId, null);
        grantOrgRole(archived, orgId);
        addMembership(archived, ScopeType.ORGANIZATION, orgId, null);
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(archived, teamId))
                .as("生存条件は FROZEN 限定ではなく ACTIVE 必須である")
                .isFalse();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(archived, orgId)).isFalse();
        assertThat(userRoleRepository.findTeamIdsByUserId(archived)).isEmpty();
        assertThat(userRoleRepository.findOrganizationIdsByUserId(archived)).isEmpty();
    }

    // =====================================================================
    // AC-10: left_at 判定の非退行
    // =====================================================================

    /** AC-10: {@code left_at} 非 NULL の membership しか持たない ACTIVE ユーザーは従来どおり非所属。 */
    @Test
    @DisplayName("AC-10: left_at非NULLのmembershipしか持たないACTIVEユーザーは従来どおり非所属")
    void ac10_退会済membershipのみのACTIVEユーザーは非所属() {
        Long teamId = nextTeamId();
        Long orgId = persistOrganization();
        Long leftUser = persistActiveUser();
        addMembership(leftUser, ScopeType.TEAM, teamId, LocalDateTime.now().minusDays(1));
        addMembership(leftUser, ScopeType.ORGANIZATION, orgId, LocalDateTime.now().minusDays(1));
        flushClear();

        assertThat(userRoleRepository.existsByUserIdAndTeamId(leftUser, teamId))
                .as("users 結合の追加で left_at 判定が失われてはならない")
                .isFalse();
        assertThat(userRoleRepository.existsByUserIdAndOrganizationId(leftUser, orgId)).isFalse();
        assertThat(userRoleRepository.findTeamIdsByUserId(leftUser)).isEmpty();
        assertThat(userRoleRepository.findOrganizationIdsByUserId(leftUser)).isEmpty();
    }

    // =====================================================================
    // AC-11 / AC-12: 家族経路専用（状態を問わない在籍判定）
    // =====================================================================

    /**
     * AC-11: {@code existsAnyStatusByUserIdAndTeamId} は FROZEN ユーザーが
     * いずれかの系統に在籍行を持つとき true。
     *
     * <p>子アカウントは PENDING_PARENTAL_CONSENT / FROZEN を取り得るが、その間も保護者の
     * 家族時間割閲覧は維持する仕様のため、被参照者を数える本メソッドは状態を問わない。</p>
     */
    @Test
    @DisplayName("AC-11: existsAnyStatusByUserIdAndTeamIdはFROZENユーザーの在籍行をtrueとする（両系統）")
    void ac11_状態を問わない在籍判定はFROZENでもtrue() {
        Long teamViaRole = nextTeamId();
        Long frozenWithRole = persistUser(UserEntity.UserStatus.FROZEN);
        grantTeamRole(frozenWithRole, teamViaRole);

        Long teamViaMembership = nextTeamId();
        Long frozenWithMembership = persistUser(UserEntity.UserStatus.FROZEN);
        addMembership(frozenWithMembership, ScopeType.TEAM, teamViaMembership, null);

        Long pendingConsentTeam = nextTeamId();
        Long pendingChild = persistUser(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        addMembership(pendingChild, ScopeType.TEAM, pendingConsentTeam, null);
        flushClear();

        assertThat(userRoleRepository.existsAnyStatusByUserIdAndTeamId(frozenWithRole, teamViaRole))
                .as("user_roles 枝: 状態を問わない在籍判定は凍結ユーザーでも true であるべきである")
                .isTrue();
        assertThat(userRoleRepository.existsAnyStatusByUserIdAndTeamId(frozenWithMembership, teamViaMembership))
                .as("memberships 枝: 状態を問わない在籍判定は凍結ユーザーでも true であるべきである")
                .isTrue();
        assertThat(userRoleRepository.existsAnyStatusByUserIdAndTeamId(pendingChild, pendingConsentTeam))
                .as("保護者同意待ちの子アカウントも家族時間割の被参照者として在籍とみなすべきである")
                .isTrue();
    }

    /** AC-12: 状態は問わないが離脱は問う（{@code left_at} 非 NULL のみの在籍は false）。 */
    @Test
    @DisplayName("AC-12: existsAnyStatusByUserIdAndTeamIdはleft_at非NULLのmembershipしか持たない者にfalse")
    void ac12_状態を問わない在籍判定でも退会済は非所属() {
        Long teamId = nextTeamId();
        Long leftFrozen = persistUser(UserEntity.UserStatus.FROZEN);
        addMembership(leftFrozen, ScopeType.TEAM, teamId, LocalDateTime.now().minusDays(1));

        Long unrelatedTeam = nextTeamId();
        Long neverJoined = persistActiveUser();
        flushClear();

        assertThat(userRoleRepository.existsAnyStatusByUserIdAndTeamId(leftFrozen, teamId))
                .as("状態は問わないが離脱済みの membership は在籍とみなしてはならない")
                .isFalse();
        assertThat(userRoleRepository.existsAnyStatusByUserIdAndTeamId(neverJoined, unrelatedTeam))
                .as("在籍行が一切なければ false であるべきである")
                .isFalse();
    }
}
