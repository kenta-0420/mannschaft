package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
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
 * Issue #2786 丙層: {@code user_roles} を候補集合の唯一の起点とする周辺クエリ群が
 * {@code memberships} 専属の一般メンバーを取りこぼす欠陥の受け入れテスト（試練 = テスト先行）。
 *
 * <p>{@code V60.010} で MEMBER / SUPPORTER の在籍行は {@code user_roles} から
 * {@code memberships} へ完全移行済みであり、{@code user_roles} に残るのは
 * SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST / JOBBER のみである。そのため
 * 「候補集合を {@code user_roles} から取り、ロール名で絞り込んでいない」クエリは
 * すべて一般メンバーを取りこぼす。</p>
 *
 * <p>対象は下記（いずれもロール名による限定を持たない）:</p>
 * <ul>
 *   <li>{@link UserRoleRepository#findUserIdsByScope(String, Long)}</li>
 *   <li>{@link UserRoleRepository#countMembersByScope(String, Long)}</li>
 *   <li>{@link UserRoleRepository#findEmailsByScope(String, Long)}</li>
 *   <li>{@link UserRoleRepository#findUserIdAndEmailByScope(String, Long)}</li>
 *   <li>{@link UserRoleRepository#findUserIdsByOrganizationIdAndPermissionName(Long, String)}</li>
 *   <li>{@link UserRoleRepository#findTeamIdsByOrganizationId(Long)}</li>
 *   <li>{@link UserRoleRepository#countSharedTeam(Long, Long)}</li>
 *   <li>{@link UserRoleRepository#countActiveMembers(String, Long, LocalDateTime)}</li>
 *   <li>{@link UserRoleRepository#findOrganizationIdsByUserId(Long)}</li>
 * </ul>
 *
 * <p>ロール名で ADMIN / DEPUTY_ADMIN / SYSTEM_ADMIN / JOBBER に限定しているクエリ群は
 * 設計通りであり、本テストは<b>それらの挙動が一切変わらないこと</b>を陽性対照として
 * 同居させる（{@code findAdminUserIdsByOrganizationId} /
 * {@code countMembersByScopeAndRole} / {@code findEmailsByScopeAndRole}）。</p>
 *
 * <p>実 MySQL（Testcontainers）に 2 系統の在籍行を実際に永続化して検証する。
 * snapshot やクエリ結果をスタブしたユニットテストでは 2 系統の非対称を再現できず、
 * green のまま本番だけ壊れるため、モック・スタブでの再現は行わない。</p>
 */
@Transactional
@DisplayName("Issue #2786 丙層: user_roles 基底の周辺スコープクエリが memberships 専属メンバーを取りこぼす")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRolePeripheralScopeMembershipsTest extends AbstractMySqlIntegrationTest {

    private static final String TEAM = "TEAM";
    private static final String ORGANIZATION = "ORGANIZATION";

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 本テスト専用のチーム ID 採番（team_org_memberships / スコープ ID のみ参照するため teams 行は不要）。 */
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー（甲層 UserRoleOrgDescendantsMembershipsAudienceTest の金型を踏襲）
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long nextTeamId() {
        return 786_000L + TEAM_SEQ.incrementAndGet();
    }

    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("i2786-org-" + n)
                .name("2786テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        return org.getId();
    }

    private UserEntity persistUserEntity(UserEntity.UserStatus status) {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("i2786-peri-" + n + "@example.com")
                .lastName("周辺")
                .firstName("照会" + n)
                .displayName("周辺照会" + n)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user;
    }

    private UserEntity persistActiveUserEntity() {
        return persistUserEntity(UserEntity.UserStatus.ACTIVE);
    }

    private Long persistActiveUser() {
        return persistActiveUserEntity().getId();
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

    private void softDeleteUser(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void setLastLoginAt(Long userId, LocalDateTime at) {
        em.createNativeQuery("UPDATE users SET last_login_at = :at WHERE id = :id")
                .setParameter("at", at)
                .setParameter("id", userId)
                .executeUpdate();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /** {@code findUserIdAndEmailByScope} の第 1 列（userId）を Long として取り出す。 */
    private List<Long> userIdsFromPairs(List<Object[]> pairs) {
        return pairs.stream().map(row -> ((Number) row[0]).longValue()).toList();
    }

    // =====================================================================
    // AC-19: スコープ配下の在籍者を返す 4 本
    // =====================================================================

    /**
     * AC-19: {@code findUserIdsByScope} / {@code countMembersByScope} /
     * {@code findEmailsByScope} / {@code findUserIdAndEmailByScope} が
     * TEAM スコープで {@code memberships} 専属の一般メンバーを含むこと。
     */
    @Test
    @DisplayName("AC-19: TEAMスコープの4本がmemberships専属の一般メンバーを含む")
    void ac19_TEAMスコープの4本がmemberships専属メンバーを含む() {
        Long teamId = nextTeamId();

        UserEntity membershipsOnly = persistActiveUserEntity();
        Long memberId = membershipsOnly.getId();
        String memberEmail = membershipsOnly.getEmail();
        addMembership(memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByScope(TEAM, teamId))
                .as("一斉通知の宛先は memberships 専属の一般メンバーを含むべきである")
                .contains(memberId);
        assertThat(userRoleRepository.countMembersByScope(TEAM, teamId))
                .as("スコープのメンバー数は memberships 専属の一般メンバー 1 名を数えるべきである")
                .isEqualTo(1);
        assertThat(userRoleRepository.findEmailsByScope(TEAM, teamId))
                .as("メール配信先は memberships 専属の一般メンバーのアドレスを含むべきである")
                .contains(memberEmail);
        assertThat(userIdsFromPairs(userRoleRepository.findUserIdAndEmailByScope(TEAM, teamId)))
                .as("userId/email ペアも memberships 専属の一般メンバーを含むべきである")
                .contains(memberId);
    }

    /**
     * AC-19: 同じ 4 本が ORGANIZATION スコープでも
     * {@code memberships} 専属の一般メンバーを含むこと。
     */
    @Test
    @DisplayName("AC-19: ORGANIZATIONスコープの4本もmemberships専属の一般メンバーを含む")
    void ac19_ORGANIZATIONスコープの4本もmemberships専属メンバーを含む() {
        Long orgId = persistOrganization(null);

        UserEntity membershipsOnly = persistActiveUserEntity();
        Long memberId = membershipsOnly.getId();
        String memberEmail = membershipsOnly.getEmail();
        addMembership(memberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByScope(ORGANIZATION, orgId))
                .as("組織スコープの一斉通知も memberships 専属の一般メンバーを含むべきである")
                .contains(memberId);
        assertThat(userRoleRepository.countMembersByScope(ORGANIZATION, orgId))
                .as("組織スコープのメンバー数は memberships 専属の一般メンバー 1 名を数えるべきである")
                .isEqualTo(1);
        assertThat(userRoleRepository.findEmailsByScope(ORGANIZATION, orgId))
                .contains(memberEmail);
        assertThat(userIdsFromPairs(userRoleRepository.findUserIdAndEmailByScope(ORGANIZATION, orgId)))
                .contains(memberId);
    }

    /**
     * AC-19【陽性対照】: {@code user_roles} に ADMIN 行のみを持つ役職者が
     * 4 本すべてで従来どおり含まれること。
     *
     * <p>候補集合を {@code memberships} へ広げる過程で {@code user_roles} 由来の
     * 役職者を落とす逆向きの回帰が起きないことを締める番人である。</p>
     */
    @Test
    @DisplayName("AC-19【陽性対照】: user_roles専属のADMIN役職者は4本すべてで従来どおり含まれる")
    void ac19_陽性対照_userRoles専属の役職者は従来どおり含まれる() {
        Long teamId = nextTeamId();

        UserEntity admin = persistActiveUserEntity();
        Long adminId = admin.getId();
        grantTeamRole(adminId, teamId, "ADMIN", 2);
        // memberships 専属の一般メンバーも同居させ、合算されることを同時に締める
        Long memberId = persistActiveUser();
        addMembership(memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByScope(TEAM, teamId))
                .as("役職者と一般メンバーの双方が宛先に含まれるべきである")
                .contains(adminId, memberId);
        assertThat(userRoleRepository.countMembersByScope(TEAM, teamId))
                .as("役職者 1 名と一般メンバー 1 名の合計 2 名となるべきである")
                .isEqualTo(2);
        assertThat(userRoleRepository.findEmailsByScope(TEAM, teamId))
                .as("役職者のメールアドレスは従来どおり返るべきである")
                .contains(admin.getEmail());
        assertThat(userIdsFromPairs(userRoleRepository.findUserIdAndEmailByScope(TEAM, teamId)))
                .contains(adminId, memberId);
    }

    /**
     * AC-19【重複排除】: 両系統に行を持つ者が 4 本すべてで 1 件として扱われること。
     */
    @Test
    @DisplayName("AC-19【重複排除】: 両系統に行を持つ者は4本すべてで1件として扱われる")
    void ac19_重複排除_両系統保有者は1件として扱われる() {
        Long teamId = nextTeamId();

        UserEntity dual = persistActiveUserEntity();
        Long dualId = dual.getId();
        grantTeamRole(dualId, teamId, "ADMIN", 2);
        addMembership(dualId, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByScope(TEAM, teamId))
                .as("両系統に在籍行があっても userId は 1 件に畳まれるべきである")
                .containsExactly(dualId);
        assertThat(userRoleRepository.countMembersByScope(TEAM, teamId))
                .as("両系統に在籍行があってもメンバー数は 1 名である")
                .isEqualTo(1);
        assertThat(userRoleRepository.findEmailsByScope(TEAM, teamId))
                .containsExactly(dual.getEmail());
        assertThat(userIdsFromPairs(userRoleRepository.findUserIdAndEmailByScope(TEAM, teamId)))
                .containsExactly(dualId);
    }

    /**
     * AC-19【境界】: 退会済 membership・論理削除済ユーザー・非 ACTIVE ユーザーが
     * 4 本すべてに含まれないこと。
     */
    @Test
    @DisplayName("AC-19【境界】: 退会済membership・論理削除済・非ACTIVEは4本すべてに含まれない")
    void ac19_境界_退会済と論理削除済と非ACTIVEは含まれない() {
        Long teamId = nextTeamId();

        Long liveMember = persistActiveUser();
        addMembership(liveMember, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);

        Long leftMember = persistActiveUser();
        addMembership(leftMember, ScopeType.TEAM, teamId, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        Long frozenViaMemberships = persistUserEntity(UserEntity.UserStatus.FROZEN).getId();
        addMembership(frozenViaMemberships, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        Long frozenViaUserRoles = persistUserEntity(UserEntity.UserStatus.FROZEN).getId();
        grantTeamRole(frozenViaUserRoles, teamId, "ADMIN", 2);
        Long deletedViaMemberships = persistActiveUser();
        addMembership(deletedViaMemberships, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        Long deletedViaUserRoles = persistActiveUser();
        grantTeamRole(deletedViaUserRoles, teamId, "ADMIN", 2);

        em.flush();
        softDeleteUser(deletedViaMemberships);
        softDeleteUser(deletedViaUserRoles);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByScope(TEAM, teamId))
                .as("在籍中の一般メンバー 1 名のみが宛先となるべきである")
                .containsExactly(liveMember);
        assertThat(userRoleRepository.countMembersByScope(TEAM, teamId))
                .as("除外対象を数えず 1 名となるべきである")
                .isEqualTo(1);
        assertThat(userRoleRepository.findEmailsByScope(TEAM, teamId))
                .as("除外対象のメールアドレスを配信先に混ぜてはならない")
                .hasSize(1);
        assertThat(userIdsFromPairs(userRoleRepository.findUserIdAndEmailByScope(TEAM, teamId)))
                .containsExactly(liveMember);
    }

    // =====================================================================
    // AC-19 派生: countActiveMembers
    // =====================================================================

    /**
     * AC-19 派生: {@code countActiveMembers} が {@code memberships} 専属の
     * 一般メンバーを数えること（{@code last_login_at} による絞り込みは維持）。
     */
    @Test
    @DisplayName("AC-19派生: countActiveMembersがmemberships専属の一般メンバーを数える（last_login_at絞り込みは維持）")
    void ac19派生_countActiveMembersがmemberships専属メンバーを数える() {
        Long teamId = nextTeamId();
        LocalDateTime since = LocalDateTime.now().minusDays(7);

        Long recentMember = persistActiveUser();
        addMembership(recentMember, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        Long staleMember = persistActiveUser();
        addMembership(staleMember, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        Long recentAdmin = persistActiveUser();
        grantTeamRole(recentAdmin, teamId, "ADMIN", 2);

        em.flush();
        setLastLoginAt(recentMember, LocalDateTime.now().minusDays(1));
        setLastLoginAt(staleMember, LocalDateTime.now().minusDays(30));
        setLastLoginAt(recentAdmin, LocalDateTime.now().minusDays(1));
        flushClear();

        assertThat(userRoleRepository.countActiveMembers(TEAM, teamId, since))
                .as("直近ログインの一般メンバー 1 名と役職者 1 名の合計 2 名を数え、"
                        + "最終ログインが古い一般メンバーは除外されるべきである")
                .isEqualTo(2);
    }

    // =====================================================================
    // AC-22: countSharedTeam（DM 受信制限）
    // =====================================================================

    /**
     * AC-22: {@code countSharedTeam} が {@code memberships} 専属同士の
     * 共通チームを検出すること。
     */
    @Test
    @DisplayName("AC-22: countSharedTeamがmemberships専属同士の共通チームを検出する")
    void ac22_countSharedTeamがmemberships専属同士の共通チームを検出する() {
        Long sharedTeam = nextTeamId();

        Long user1 = persistActiveUser();
        Long user2 = persistActiveUser();
        addMembership(user1, ScopeType.TEAM, sharedTeam, RoleKind.MEMBER, null);
        addMembership(user2, ScopeType.TEAM, sharedTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.countSharedTeam(user1, user2))
                .as("memberships 専属の一般メンバー同士でも共通チームは検出されるべきである")
                .isGreaterThan(0L);
        assertThat(userRoleRepository.existsSharedTeam(user1, user2))
                .as("DM 受信制限の判定は一般メンバー同士で true となるべきである")
                .isTrue();
    }

    /**
     * AC-22: 一方が {@code user_roles} の役職者・他方が {@code memberships} 専属の
     * 一般メンバーという混在の組でも共通チームが検出されること。
     */
    @Test
    @DisplayName("AC-22: 役職者とmemberships専属メンバーの混在でも共通チームを検出する")
    void ac22_役職者とmemberships専属メンバーの混在でも検出する() {
        Long sharedTeam = nextTeamId();

        Long admin = persistActiveUser();
        grantTeamRole(admin, sharedTeam, "ADMIN", 2);
        Long member = persistActiveUser();
        addMembership(member, ScopeType.TEAM, sharedTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.existsSharedTeam(admin, member))
                .as("役職者と一般メンバーの組でも共通チームは検出されるべきである")
                .isTrue();
        assertThat(userRoleRepository.existsSharedTeam(member, admin))
                .as("引数の順序を入れ替えても判定は対称であるべきである")
                .isTrue();
    }

    /**
     * AC-22【境界】: 共通チームを持たない 2 名・退会済 membership しか持たない 2 名は
     * 検出されないこと。
     *
     * <p>候補集合を広げる際に結合条件を緩めると、無関係な 2 名の DM が
     * 素通りする方向へ壊れる。</p>
     */
    @Test
    @DisplayName("AC-22【境界】: 共通チームを持たない2名・退会済のみの2名は検出されない")
    void ac22_境界_共通チーム無しと退会済のみは検出されない() {
        Long teamA = nextTeamId();
        Long teamB = nextTeamId();

        Long inA = persistActiveUser();
        addMembership(inA, ScopeType.TEAM, teamA, RoleKind.MEMBER, null);
        Long inB = persistActiveUser();
        addMembership(inB, ScopeType.TEAM, teamB, RoleKind.MEMBER, null);

        Long leftTeam = nextTeamId();
        Long left1 = persistActiveUser();
        addMembership(left1, ScopeType.TEAM, leftTeam, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        Long left2 = persistActiveUser();
        addMembership(left2, ScopeType.TEAM, leftTeam, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        flushClear();

        assertThat(userRoleRepository.existsSharedTeam(inA, inB))
                .as("別チームに在籍する 2 名を共通チーム所属と判定してはならない")
                .isFalse();
        assertThat(userRoleRepository.existsSharedTeam(left1, left2))
                .as("双方とも退会済の membership しか持たない場合は共通チームなしと判定すべきである")
                .isFalse();
    }

    // =====================================================================
    // AC-23: findOrganizationIdsByUserId
    // =====================================================================

    /**
     * AC-23: {@code findOrganizationIdsByUserId} が {@code memberships} 由来の
     * 所属組織を含むこと。
     *
     * <p>本メソッドは PRIVATE 子組織一覧の可視性 SQL に降ろされるため、
     * 取りこぼすと一般メンバーは自分が所属する PRIVATE 子組織を見失う。
     * javadoc の「membership ドメインとの二重管理は生じない」という前提は
     * {@code V60.010} 後は成立しない。</p>
     */
    @Test
    @DisplayName("AC-23: findOrganizationIdsByUserIdがmemberships由来の所属組織を含む")
    void ac23_findOrganizationIdsByUserIdがmemberships由来の組織を含む() {
        Long orgViaMemberships = persistOrganization(null);
        Long unrelatedOrg = persistOrganization(null);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.ORGANIZATION, orgViaMemberships, RoleKind.MEMBER, null);
        // 無関係な組織にも別人を置き、母集団が広がりすぎないことを同時に締める
        addMembership(persistActiveUser(), ScopeType.ORGANIZATION, unrelatedOrg, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findOrganizationIdsByUserId(membershipsOnly))
                .as("memberships にのみ在籍行を持つ一般メンバーの所属組織が解決されるべきである")
                .containsExactly(orgViaMemberships);
    }

    /**
     * AC-23【陽性対照・重複排除】: {@code user_roles} 由来の所属組織が従来どおり返り、
     * 両系統に行を持つ組織が重複しないこと。
     */
    @Test
    @DisplayName("AC-23【陽性対照】: user_roles由来の所属組織は従来どおり返り両系統重複時も1件")
    void ac23_陽性対照_userRoles由来の組織は従来どおり返る() {
        Long orgViaUserRoles = persistOrganization(null);
        Long orgViaBoth = persistOrganization(null);

        Long user = persistActiveUser();
        grantOrgRole(user, orgViaUserRoles, "ADMIN", 2);
        grantOrgRole(user, orgViaBoth, "DEPUTY_ADMIN", 3);
        addMembership(user, ScopeType.ORGANIZATION, orgViaBoth, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findOrganizationIdsByUserId(user))
                .as("user_roles 由来の 2 組織が返り、両系統に行を持つ組織も 1 件に畳まれるべきである")
                .containsExactlyInAnyOrder(orgViaUserRoles, orgViaBoth);
    }

    /**
     * AC-23【境界】: 退会済 membership 由来の組織が含まれないこと。
     */
    @Test
    @DisplayName("AC-23【境界】: 退会済membership由来の組織は所属組織に含まれない")
    void ac23_境界_退会済membership由来の組織は含まれない() {
        Long liveOrg = persistOrganization(null);
        Long leftOrg = persistOrganization(null);

        Long user = persistActiveUser();
        addMembership(user, ScopeType.ORGANIZATION, liveOrg, RoleKind.MEMBER, null);
        addMembership(user, ScopeType.ORGANIZATION, leftOrg, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));
        flushClear();

        assertThat(userRoleRepository.findOrganizationIdsByUserId(user))
                .as("退会済 membership の組織を所属組織に混ぜてはならない")
                .containsExactly(liveOrg);
    }

    // =====================================================================
    // findTeamIdsByOrganizationId
    // =====================================================================

    /**
     * AC-19 派生: {@code findTeamIdsByOrganizationId} が
     * {@code memberships} 専属の一般メンバーしかいない配下チームを返すこと。
     *
     * <p>本メソッドは組織告知の {@code target_team_ids} 検証に使われる。
     * 一般メンバーだけで構成される配下チームが返らないと、そのチームを
     * 宛先に指定した正当な組織告知が拒否される。</p>
     */
    @Test
    @DisplayName("AC-19派生: findTeamIdsByOrganizationIdがmemberships専属メンバーのみの配下チームを返す")
    void ac19派生_findTeamIdsByOrganizationIdがmemberships専属チームを返す() {
        Long orgId = persistOrganization(null);
        Long memberOnlyTeam = nextTeamId();
        linkTeamToOrg(memberOnlyTeam, orgId);

        addMembership(persistActiveUser(), ScopeType.TEAM, memberOnlyTeam, RoleKind.MEMBER, null);

        // 別組織の配下チーム（巻き込み防止の対照）
        Long otherOrg = persistOrganization(null);
        Long otherTeam = nextTeamId();
        linkTeamToOrg(otherTeam, otherOrg);
        addMembership(persistActiveUser(), ScopeType.TEAM, otherTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findTeamIdsByOrganizationId(orgId))
                .as("一般メンバーのみで構成される配下チームも組織配下チームとして返るべきである")
                .contains(memberOnlyTeam);
        assertThat(userRoleRepository.findTeamIdsByOrganizationId(orgId))
                .as("別組織の配下チームを巻き込んではならない")
                .doesNotContain(otherTeam);
    }

    // =====================================================================
    // findUserIdsByOrganizationIdAndPermissionName
    // =====================================================================

    /**
     * AC-19 派生: {@code findUserIdsByOrganizationIdAndPermissionName} が
     * {@code memberships} 専属の一般メンバーの権限を評価すること。
     *
     * <p>MEMBER ロールに既定付与された権限を持つ一般メンバーは、
     * {@code user_roles} に行が無いという理由だけで警告通知の宛先から落ちてはならない。</p>
     *
     * <p><b>本テストが暴いた別の欠陥</b>: 現状の実装は取りこぼす以前に、実在しない列を
     * 参照しており全呼び出しで {@code SQLGrammarException} となる。
     * {@code permission_groups} 系の関連表が持つ列は {@code group_id} であって
     * {@code permission_group_id} ではなく、{@code user_permission_groups} には
     * {@code organization_id} 列自体が存在しない（{@code V2.008} / {@code V2.009}、
     * 以降の migration でも追加されていない）。memberships 対応と併せて
     * 実スキーマに合わせた修正が必要である。</p>
     */
    @Test
    @DisplayName("AC-19派生: findUserIdsByOrganizationIdAndPermissionNameがmemberships専属メンバーの権限を評価する")
    void ac19派生_権限保有者照会がmemberships専属メンバーを評価する() {
        Long orgId = persistOrganization(null);
        String permissionName = "I2786_TEST_PERMISSION";
        Long memberRoleId = persistRoleIfNeeded("MEMBER", 4);
        Long permissionId = persistPermission(permissionName);
        grantRolePermission(memberRoleId, permissionId);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgId, permissionName))
                .as("MEMBER ロール既定の権限は memberships 専属の一般メンバーにも評価されるべきである")
                .contains(membershipsOnly);
    }

    /**
     * AC-19 派生【陽性対照】: {@code user_roles} 由来の役職者の権限評価が変わらないこと。
     *
     * <p>本来は現時点で green であるべき陽性対照だが、上記の実在しない列参照により
     * 現状は例外で赤になる。実スキーマへ合わせた修正後、この対照が green を保つことで
     * 「一般メンバーへ権限が拡散していない」ことを締める。</p>
     */
    @Test
    @DisplayName("AC-19派生【陽性対照】: user_roles由来の役職者の権限評価は従来どおり")
    void ac19派生_陽性対照_userRoles由来の役職者の権限評価は不変() {
        Long orgId = persistOrganization(null);
        String permissionName = "I2786_ADMIN_PERMISSION";
        Long adminRoleId = persistRoleIfNeeded("ADMIN", 2);
        Long permissionId = persistPermission(permissionName);
        grantRolePermission(adminRoleId, permissionId);

        Long admin = persistActiveUser();
        grantOrgRole(admin, orgId, "ADMIN", 2);
        // 当該権限を持たない一般メンバー（母集団が広がりすぎないことを締める）
        Long plainMember = persistActiveUser();
        addMembership(plainMember, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgId, permissionName))
                .as("ADMIN ロール既定の権限は役職者にのみ評価され、一般メンバーへ拡散してはならない")
                .containsExactly(admin);
    }

    // =====================================================================
    // 陽性対照: ロール名で限定しているクエリ群は挙動が変わらない
    // =====================================================================

    /**
     * 【陽性対照】ロール名で ADMIN / DEPUTY_ADMIN に限定しているクエリ群
     * （{@code findAdminUserIdsByOrganizationId} / {@code countMembersByScopeAndRole} /
     * {@code findEmailsByScopeAndRole}）の挙動が一切変わらないこと。
     *
     * <p>これらは設計通り {@code user_roles} のみを見るべきクエリであり、
     * 丙層の修正で一般メンバーを混ぜ込んではならない。管理者宛の通知に
     * 一般メンバーが混ざる情報漏洩の方向へ壊れることを防ぐ番人である。</p>
     */
    @Test
    @DisplayName("【陽性対照】ロール名限定クエリ群は一般メンバーを混ぜず挙動が変わらない")
    void 陽性対照_ロール名限定クエリ群の挙動は不変() {
        Long orgId = persistOrganization(null);

        UserEntity admin = persistActiveUserEntity();
        grantOrgRole(admin.getId(), orgId, "ADMIN", 2);
        UserEntity deputy = persistActiveUserEntity();
        grantOrgRole(deputy.getId(), orgId, "DEPUTY_ADMIN", 3);
        // memberships 専属の一般メンバー 2 名（混入したら検出される）
        Long member1 = persistActiveUser();
        addMembership(member1, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        Long member2 = persistActiveUser();
        addMembership(member2, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(userRoleRepository.findAdminUserIdsByOrganizationId(orgId))
                .as("管理者宛の宛先に memberships 専属の一般メンバーを混ぜてはならない")
                .containsExactlyInAnyOrder(admin.getId(), deputy.getId());
        assertThat(userRoleRepository.countMembersByScopeAndRole(ORGANIZATION, orgId, "ADMIN"))
                .as("ロール名 ADMIN で限定した人数は 1 名のままである")
                .isEqualTo(1);
        assertThat(userRoleRepository.findEmailsByScopeAndRole(ORGANIZATION, orgId, "ADMIN"))
                .as("ロール名 ADMIN で限定したメール配信先は変わらない")
                .containsExactly(admin.getEmail());
        assertThat(userRoleRepository.findUserIdAndEmailByScopeAndRole(ORGANIZATION, orgId, "DEPUTY_ADMIN"))
                .as("ロール名 DEPUTY_ADMIN で限定したペアは 1 件のままである")
                .hasSize(1);
    }

    // ---------------------------------------------------------------------
    // 権限フィクスチャ
    // ---------------------------------------------------------------------

    private Long persistPermission(String name) {
        PermissionEntity permission = PermissionEntity.builder()
                .name(name)
                .displayName(name)
                .scope(PermissionEntity.Scope.ORGANIZATION)
                .build();
        em.persist(permission);
        em.flush();
        return permission.getId();
    }

    private void grantRolePermission(Long roleId, Long permissionId) {
        RolePermissionEntity rp = RolePermissionEntity.builder()
                .roleId(roleId)
                .permissionId(permissionId)
                .isDefault(true)
                .build();
        em.persist(rp);
        em.flush();
    }
}
