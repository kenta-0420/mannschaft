package com.mannschaft.app.role;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 除名・退会と在籍（{@code memberships}）の整合を実 MySQL に対して固定する契約テスト。
 *
 * <h2>守る仕様</h2>
 * <p>認可の在籍判定は {@code memberships.left_at IS NULL} を真実の源とする
 * （{@link AccessControlService#isMember} / {@link AccessControlService#findAffiliatedScopeIds}）。
 * したがって除名・退会の各経路は、{@code user_roles} の削除と<b>同一トランザクション内で</b>
 * {@code memberships.left_at} を確定させなければならない。本テストは
 * {@link RoleService} の 3 経路すべてについてこれを固定する。</p>
 *
 * <h2>受け入れ条件（AC）</h2>
 * <ul>
 *   <li>AC1: {@code removeMember}（管理者による除名）後、対象者は
 *       {@code findAffiliatedScopeIds} / {@code isMember} のいずれにも現れない</li>
 *   <li>AC2: AC1 の際 {@code left_at} が確定し {@code leave_reason=REMOVED} が記録される</li>
 *   <li>AC3: {@code leaveScope}（自主退会）後も同様に在籍が消え、
 *       {@code leave_reason=SELF} が記録される</li>
 *   <li>AC4: {@code removeMemberWithoutAdminCheck}（退会者 purge 経路）後も在籍が消える</li>
 *   <li>AC5: <b>正常系</b> — 除名されていない在籍者は引き続き
 *       {@code findAffiliatedScopeIds} / {@code isMember} に現れる（締め出し退行の防止）</li>
 *   <li>AC6: 上記を TEAM / ORGANIZATION 双方で満たす</li>
 * </ul>
 *
 * <p>テスト環境は {@code application-test.yml} の {@code ddl-auto=create} + {@code flyway.enabled=false}
 * のため {@code roles} テーブルが seed されない。{@code ADMIN} ロールの解決が必要なため、
 * 各テストの冒頭で本番 seed（V2.014）と同一 priority の固定 6 ロールを投入する。</p>
 */
@DisplayName("除名・退会と memberships 在籍の整合 契約テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RoleMembershipLeaveContractIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired private RoleService roleService;
    @Autowired private AccessControlService accessControlService;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private Long adminRoleId;
    private Long memberRoleId;

    @BeforeEach
    void setUp() {
        truncateAll();
        saveRole("SYSTEM_ADMIN", 1, true);
        adminRoleId = saveRole("ADMIN", 2, false);
        saveRole("DEPUTY_ADMIN", 3, false);
        memberRoleId = saveRole("MEMBER", 4, false);
        saveRole("SUPPORTER", 5, false);
        saveRole("GUEST", 6, false);
    }

    /**
     * テスト境界で作成したデータを確実に消し込む。
     *
     * <p>本テストは（除名・退会の実コミットを固定する目的で）{@code @Transactional} を付けず
     * 各操作を即時コミットする。そのため {@code @BeforeEach} の消し込みだけでは
     * <b>最後のテスト実行後に自分が投入した行が commit 済みで残留</b>し、同一 shard で
     * Spring コンテキストと Testcontainers コンテナを共有する他テストの seed と衝突しうる
     * （特に {@code roles.name} は unique 制約を持ち、非冪等な生 INSERT を行う既存テストと
     * 二重登録で衝突する）。前後対称に消し込むことで、本テストを「後始末まで行う良き市民」に保つ。</p>
     */
    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        userRoleRepository.deleteAll();
        membershipRepository.deleteAll();
        teamRepository.deleteAll();
        organizationRepository.deleteAll();
        roleRepository.deleteAll();
    }

    // ───────────────────────────────────────────────────────────────────
    // ヘルパー
    // ───────────────────────────────────────────────────────────────────

    private Long saveRole(String name, int priority, boolean isSystem) {
        return roleRepository.save(RoleEntity.builder()
                .name(name).displayName(name).priority(priority).isSystem(isSystem).build()).getId();
    }

    private Long saveTeam() {
        return teamRepository.save(TeamEntity.builder()
                .slug("team-" + SEQ.incrementAndGet())
                .name("契約テスト用チーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build()).getId();
    }

    private Long saveOrg() {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("org-" + SEQ.incrementAndGet())
                .name("契約テスト用組織")
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build()).getId();
    }

    /** 招待参加後と同じ形（user_roles 行 + アクティブ membership）で在籍させる。 */
    private void enroll(Long userId, ScopeType scopeType, Long scopeId, Long roleId) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> MembershipTestHelper.insertActiveUser(em, userId));
        var builder = UserRoleEntity.builder().userId(userId).roleId(roleId);
        if (scopeType == ScopeType.TEAM) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
        userRoleRepository.save(builder.build());
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private Optional<MembershipEntity> membershipOf(Long userId, ScopeType scopeType, Long scopeId) {
        return membershipRepository.findAll().stream()
                .filter(m -> userId.equals(m.getUserId())
                        && m.getScopeType() == scopeType
                        && scopeId.equals(m.getScopeId()))
                .findFirst();
    }

    private String scopeName(ScopeType scopeType) {
        return scopeType.name();
    }

    /** 在籍が完全に消えている（在籍列挙・メンバー判定の双方から消えている）ことを検証する。 */
    private void assertNotAffiliated(Long userId, ScopeType scopeType, Long scopeId) {
        assertThat(accessControlService.findAffiliatedScopeIds(userId, scopeName(scopeType)))
                .doesNotContain(scopeId);
        assertThat(accessControlService.isMember(userId, scopeId, scopeName(scopeType))).isFalse();
    }

    /** 在籍が保たれている（正常系）ことを検証する。 */
    private void assertAffiliated(Long userId, ScopeType scopeType, Long scopeId) {
        assertThat(accessControlService.findAffiliatedScopeIds(userId, scopeName(scopeType)))
                .contains(scopeId);
        assertThat(accessControlService.isMember(userId, scopeId, scopeName(scopeType))).isTrue();
    }

    // ───────────────────────────────────────────────────────────────────
    // AC1 / AC2 / AC5: removeMember（管理者による除名）
    // ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeMember（管理者による除名）")
    class RemoveMember {

        @Test
        @DisplayName("AC1/AC2-team: 除名された利用者は在籍列挙から消え leave_reason=REMOVED が記録される")
        void ac1_team() {
            Long teamId = saveTeam();
            Long operator = 9001L;
            Long target = 9002L;
            enroll(operator, ScopeType.TEAM, teamId, adminRoleId);
            enroll(target, ScopeType.TEAM, teamId, memberRoleId);
            assertAffiliated(target, ScopeType.TEAM, teamId);

            roleService.removeMember(teamId, "TEAM", target, operator);

            assertNotAffiliated(target, ScopeType.TEAM, teamId);
            MembershipEntity m = membershipOf(target, ScopeType.TEAM, teamId).orElseThrow();
            assertThat(m.getLeftAt()).isNotNull();
            assertThat(m.getLeaveReason()).isEqualTo(LeaveReason.REMOVED);
        }

        @Test
        @DisplayName("AC1/AC2-org: 除名された利用者は在籍列挙から消え leave_reason=REMOVED が記録される")
        void ac1_org() {
            Long orgId = saveOrg();
            Long operator = 9011L;
            Long target = 9012L;
            enroll(operator, ScopeType.ORGANIZATION, orgId, adminRoleId);
            enroll(target, ScopeType.ORGANIZATION, orgId, memberRoleId);
            assertAffiliated(target, ScopeType.ORGANIZATION, orgId);

            roleService.removeMember(orgId, "ORGANIZATION", target, operator);

            assertNotAffiliated(target, ScopeType.ORGANIZATION, orgId);
            MembershipEntity m = membershipOf(target, ScopeType.ORGANIZATION, orgId).orElseThrow();
            assertThat(m.getLeftAt()).isNotNull();
            assertThat(m.getLeaveReason()).isEqualTo(LeaveReason.REMOVED);
        }

        @Test
        @DisplayName("AC5-team: 除名されていない在籍者は在籍列挙に残り続ける（締め出し退行の防止）")
        void ac5_team_bystanderKeepsAffiliation() {
            Long teamId = saveTeam();
            Long operator = 9021L;
            Long target = 9022L;
            Long bystander = 9023L;
            enroll(operator, ScopeType.TEAM, teamId, adminRoleId);
            enroll(target, ScopeType.TEAM, teamId, memberRoleId);
            enroll(bystander, ScopeType.TEAM, teamId, memberRoleId);

            roleService.removeMember(teamId, "TEAM", target, operator);

            assertNotAffiliated(target, ScopeType.TEAM, teamId);
            // 巻き添えで在籍を失っていないこと。
            assertAffiliated(bystander, ScopeType.TEAM, teamId);
            assertAffiliated(operator, ScopeType.TEAM, teamId);
            assertThat(membershipOf(bystander, ScopeType.TEAM, teamId).orElseThrow().getLeftAt()).isNull();
        }

        @Test
        @DisplayName("AC5-org: 別スコープの在籍は除名の影響を受けない")
        void ac5_org_otherScopeUntouched() {
            Long orgA = saveOrg();
            Long orgB = saveOrg();
            Long operator = 9031L;
            Long target = 9032L;
            enroll(operator, ScopeType.ORGANIZATION, orgA, adminRoleId);
            enroll(target, ScopeType.ORGANIZATION, orgA, memberRoleId);
            enroll(target, ScopeType.ORGANIZATION, orgB, memberRoleId);

            roleService.removeMember(orgA, "ORGANIZATION", target, operator);

            assertNotAffiliated(target, ScopeType.ORGANIZATION, orgA);
            assertAffiliated(target, ScopeType.ORGANIZATION, orgB);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // AC3: leaveScope（自主退会）
    // ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("leaveScope（自主退会）")
    class LeaveScope {

        @Test
        @DisplayName("AC3-team: 自主退会後は在籍列挙から消え leave_reason=SELF が記録される")
        void ac3_team() {
            Long teamId = saveTeam();
            Long user = 9041L;
            enroll(user, ScopeType.TEAM, teamId, memberRoleId);
            assertAffiliated(user, ScopeType.TEAM, teamId);

            roleService.leaveScope(user, teamId, "TEAM");

            assertNotAffiliated(user, ScopeType.TEAM, teamId);
            MembershipEntity m = membershipOf(user, ScopeType.TEAM, teamId).orElseThrow();
            assertThat(m.getLeftAt()).isNotNull();
            assertThat(m.getLeaveReason()).isEqualTo(LeaveReason.SELF);
        }

        @Test
        @DisplayName("AC3-org: 自主退会後は在籍列挙から消え leave_reason=SELF が記録される")
        void ac3_org() {
            Long orgId = saveOrg();
            Long user = 9051L;
            enroll(user, ScopeType.ORGANIZATION, orgId, memberRoleId);
            assertAffiliated(user, ScopeType.ORGANIZATION, orgId);

            roleService.leaveScope(user, orgId, "ORGANIZATION");

            assertNotAffiliated(user, ScopeType.ORGANIZATION, orgId);
            MembershipEntity m = membershipOf(user, ScopeType.ORGANIZATION, orgId).orElseThrow();
            assertThat(m.getLeftAt()).isNotNull();
            assertThat(m.getLeaveReason()).isEqualTo(LeaveReason.SELF);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // AC4: removeMemberWithoutAdminCheck（退会者 purge 経路）
    // ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeMemberWithoutAdminCheck（退会者 purge 経路）")
    class RemoveMemberWithoutAdminCheck {

        @Test
        @DisplayName("AC4-team: purge 経路でも在籍列挙から消える")
        void ac4_team() {
            Long teamId = saveTeam();
            Long user = 9061L;
            enroll(user, ScopeType.TEAM, teamId, memberRoleId);
            assertAffiliated(user, ScopeType.TEAM, teamId);

            roleService.removeMemberWithoutAdminCheck(teamId, "TEAM", user);

            assertNotAffiliated(user, ScopeType.TEAM, teamId);
            assertThat(membershipOf(user, ScopeType.TEAM, teamId).orElseThrow().getLeftAt()).isNotNull();
        }

        @Test
        @DisplayName("AC4-org: 最後の ADMIN でも purge 経路は在籍を終了できる（ADMIN 保護に阻まれない）")
        void ac4_org_lastAdminNotBlocked() {
            Long orgId = saveOrg();
            Long user = 9071L;
            enroll(user, ScopeType.ORGANIZATION, orgId, adminRoleId);
            assertAffiliated(user, ScopeType.ORGANIZATION, orgId);

            roleService.removeMemberWithoutAdminCheck(orgId, "ORGANIZATION", user);

            assertNotAffiliated(user, ScopeType.ORGANIZATION, orgId);
            assertThat(membershipOf(user, ScopeType.ORGANIZATION, orgId).orElseThrow().getLeftAt()).isNotNull();
        }
    }
}
