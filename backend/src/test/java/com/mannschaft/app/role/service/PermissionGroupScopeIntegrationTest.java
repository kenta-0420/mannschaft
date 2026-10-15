package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #2797 / CMP-040 の試練（テスト先行）: 権限グループ経路のサービス層受け入れテスト。
 *
 * <p>本クラスが担う受け入れ条件:</p>
 * <ul>
 *   <li><b>AC-8</b>: {@code ThresholdAlertEvaluationService} の宛先解決が実 DB で通ること</li>
 *   <li><b>AC-11</b>: 他組織の権限グループ ID を指定した付与要求が拒否されること（BOLA）</li>
 *   <li><b>AC-12</b>: 宛先解決がループ内クエリを増やしていないこと（N+1 の非退行）</li>
 * </ul>
 *
 * <p>いずれも実 DB（Testcontainers）で実サービスを通す。宛先解決は
 * {@code SQLSyntaxErrorException} で必ず落ちる経路を含むため、リポジトリをモックすると
 * 欠陥を一度も踏まないまま green になる。したがってモック・スタブは用いない。</p>
 *
 * <p>test profile は {@code ddl-auto: create} かつ Flyway 無効のため、権限・ロール・組織・
 * ユーザーはすべて本テスト内で永続化する。</p>
 */
@Transactional
@DisplayName("Issue #2797: 権限グループ経路のサービス層（宛先解決・付与スコープ・N+1 非退行）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PermissionGroupScopeIntegrationTest extends AbstractMySqlIntegrationTest {

    /** {@code ThresholdAlertEvaluationService#resolveRecipients} が参照する固定 permission 名。 */
    private static final String BUDGET_ADMIN = "BUDGET_ADMIN";

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ThresholdAlertEvaluationService thresholdAlertEvaluationService;

    @Autowired
    private PermissionGroupService permissionGroupService;

    /** AC-13 専用: テストトランザクションに依らず、実際にコミット／ロールバックさせるために使う。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long persistOrganization() {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("i2797-svc-org-" + n)
                .name("2797サービステスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        em.flush();
        return org.getId();
    }

    private Long persistActiveUser() {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("i2797-svc-" + n + "@example.com")
                .lastName("権限")
                .firstName("サービス" + n)
                .displayName("権限サービス" + n)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        em.flush();
        return user.getId();
    }

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
        addMembership(userId, organizationId, RoleKind.MEMBER);
    }

    private void addMembership(Long userId, Long organizationId, RoleKind roleKind) {
        persistRoleIfNeeded(roleKind.name(), roleKind == RoleKind.MEMBER ? 4 : 5);
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(organizationId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .build();
        em.persist(ms);
    }

    private Long persistPermission(String name) {
        // 冪等化: permissions はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは permissions の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
        List<?> found = em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name)
                .getResultList();
        if (!found.isEmpty()) {
            return ((Number) found.get(0)).longValue();
        }
        PermissionEntity permission = PermissionEntity.builder()
                .name(name)
                .displayName(name)
                .scope(PermissionEntity.Scope.ORGANIZATION)
                .build();
        em.persist(permission);
        em.flush();
        return permission.getId();
    }

    /** 組織スコープの権限グループを作る（{@code team_id} は詰めない = CHECK 制約準拠）。 */
    private Long persistOrgPermissionGroup(Long organizationId) {
        return persistOrgPermissionGroup(organizationId, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
    }

    private Long persistOrgPermissionGroup(Long organizationId, PermissionGroupEntity.TargetRole targetRole) {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .organizationId(organizationId)
                .targetRole(targetRole)
                .name("2797サービス権限束" + nextSeq())
                .build();
        em.persist(group);
        em.flush();
        return group.getId();
    }

    private void addPermissionToGroup(Long groupId, Long permissionId) {
        PermissionGroupPermissionEntity pgp = PermissionGroupPermissionEntity.builder()
                .groupId(groupId)
                .permissionId(permissionId)
                .build();
        em.persist(pgp);
        em.flush();
    }

    private void assignGroupToUser(Long userId, Long groupId) {
        UserPermissionGroupEntity upg = UserPermissionGroupEntity.builder()
                .userId(userId)
                .groupId(groupId)
                .build();
        em.persist(upg);
        em.flush();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /**
     * {@code ThresholdAlertEvaluationService#resolveRecipients} を実 Bean 経由で呼ぶ。
     *
     * <p>本メソッドは private であり公開入口 {@code evaluateAndTrigger} は allocation・
     * 消化実績まで揃えないと到達しない。ここで検証したいのは「宛先解決の SQL が実 DB で通るか」
     * であるため、実 Bean（{@code @Transactional} プロキシの標的オブジェクト）に対して
     * リフレクション呼び出しする。リポジトリは実物・DB も実物であり、
     * 欠陥のある native クエリは確実に踏まれる。</p>
     */
    @SuppressWarnings("unchecked")
    private List<Long> resolveRecipients(Long organizationId) {
        Object target = AopTestUtils.getTargetObject(thresholdAlertEvaluationService);
        return (List<Long>) ReflectionTestUtils.invokeMethod(target, "resolveRecipients", organizationId);
    }

    // =====================================================================
    // AC-8: 宛先解決が実 DB で通る
    // =====================================================================

    /**
     * AC-8: {@code ThresholdAlertEvaluationService} の宛先解決が実 DB で例外なく完了し、
     * ADMIN と BUDGET_ADMIN 保有者の和集合を返すこと。
     *
     * <p>既存の {@code ThresholdAlertEvaluationServiceTest} はリポジトリを
     * {@code given(...)} でスタブしているため、native クエリが実 DB で例外になる事実を
     * 一度も踏まない。本テストはその偽の緑を埋めるためのものである。</p>
     */
    @Test
    @DisplayName("AC-8: ThresholdAlertEvaluationServiceの宛先解決が実DBで通りADMINと権限保有者の和集合を返す")
    void ac8_宛先解決が実DBで通る() {
        Long orgId = persistOrganization();
        Long budgetAdminPermissionId = persistPermission(BUDGET_ADMIN);

        Long admin = persistActiveUser();
        grantOrgRole(admin, orgId, "ADMIN", 2);

        // 権限グループ経由で BUDGET_ADMIN を持つ非役職者。
        // ADMIN / DEPUTY_ADMIN にすると findAdminUserIdsByOrganizationId 側だけで拾えてしまい、
        // 権限グループ経路を一度も踏まないまま和集合が満たされる（偽の緑）ため、
        // MEMBER の権限グループ経路でしか到達できない状態にする。
        Long budgetHolder = persistActiveUser();
        addMembership(budgetHolder, orgId, RoleKind.MEMBER);
        Long groupId = persistOrgPermissionGroup(orgId, PermissionGroupEntity.TargetRole.MEMBER);
        addPermissionToGroup(groupId, budgetAdminPermissionId);
        assignGroupToUser(budgetHolder, groupId);
        flushClear();

        List<Long> recipients = resolveRecipients(orgId);

        assertThat(recipients)
                .as("ADMIN と、権限グループ経由の BUDGET_ADMIN 保有者の双方が宛先に含まれるべきである")
                .contains(admin, budgetHolder);
    }

    /**
     * AC-8【境界】: 権限グループも BUDGET_ADMIN 保有者も居ない組織で、
     * 宛先解決が例外にならず ADMIN のみを返すこと。
     */
    @Test
    @DisplayName("AC-8【境界】: 権限グループ不在の組織でも宛先解決は例外にならずADMINのみを返す")
    void ac8_境界_権限グループ不在でも宛先解決は通る() {
        Long orgId = persistOrganization();
        persistPermission(BUDGET_ADMIN);

        Long admin = persistActiveUser();
        grantOrgRole(admin, orgId, "ADMIN", 2);
        Long member = persistActiveUser();
        addMembership(member, orgId, RoleKind.MEMBER);
        flushClear();

        assertThat(resolveRecipients(orgId))
                .as("権限グループ不在でも宛先解決は成功し、ADMIN のみが宛先となるべきである")
                .containsExactly(admin);
    }

    // =====================================================================
    // AC-11: 他組織の権限グループ ID を指定した付与は拒否される（BOLA）
    // =====================================================================

    /**
     * AC-11: 他組織に属する権限グループ ID を指定した付与要求が拒否されること。
     *
     * <p>{@code PermissionGroupService#assignUserPermissionGroups} は、削除側では
     * {@code findByScope} でスコープを絞っている一方、付与側は
     * {@code permissionGroupRepository.findById(groupId)} の存在確認しかしていない。
     * そのため A 社の ADMIN が B 社の権限グループ ID を渡すと、A 社のユーザーに
     * B 社の権限束が割り当てられる（BOLA）。</p>
     */
    @Test
    @DisplayName("AC-11: 他組織の権限グループIDを指定した付与要求は拒否される")
    void ac11_他組織の権限グループIDによる付与は拒否される() {
        Long orgA = persistOrganization();
        Long orgB = persistOrganization();

        Long adminOfA = persistActiveUser();
        grantOrgRole(adminOfA, orgA, "ADMIN", 2);
        Long targetUser = persistActiveUser();
        grantOrgRole(targetUser, orgA, "DEPUTY_ADMIN", 3);

        // B 社の権限グループ（A 社の ADMIN には触れる権限が無いはず）
        Long groupOfB = persistOrgPermissionGroup(orgB);
        flushClear();

        UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(groupOfB));

        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                targetUser, orgA, "ORGANIZATION", req, adminOfA))
                .as("他組織の権限グループ ID を指定した付与は BusinessException で拒否されるべきである")
                .isInstanceOf(BusinessException.class);

        flushClear();
        Long assigned = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_permission_groups WHERE user_id = :uid AND group_id = :gid")
                .setParameter("uid", targetUser)
                .setParameter("gid", groupOfB)
                .getSingleResult()).longValue();
        assertThat(assigned)
                .as("拒否された付与要求で割当行が作られてはならない")
                .isZero();
    }

    /**
     * AC-11【陽性対照】: 自組織の権限グループ ID を指定した付与は従来どおり成功すること。
     *
     * <p>越境を塞ぐ過程で正当な付与まで拒否する逆向きの回帰を防ぐ番人である。</p>
     */
    @Test
    @DisplayName("AC-11【陽性対照】: 自組織の権限グループIDによる付与は従来どおり成功する")
    void ac11_陽性対照_自組織の権限グループ付与は成功する() {
        Long orgA = persistOrganization();

        Long adminOfA = persistActiveUser();
        grantOrgRole(adminOfA, orgA, "ADMIN", 2);
        Long targetUser = persistActiveUser();
        grantOrgRole(targetUser, orgA, "DEPUTY_ADMIN", 3);
        Long groupOfA = persistOrgPermissionGroup(orgA);
        flushClear();

        UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(groupOfA));
        permissionGroupService.assignUserPermissionGroups(targetUser, orgA, "ORGANIZATION", req, adminOfA);
        flushClear();

        Long assigned = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_permission_groups WHERE user_id = :uid AND group_id = :gid")
                .setParameter("uid", targetUser)
                .setParameter("gid", groupOfA)
                .getSingleResult()).longValue();
        assertThat(assigned)
                .as("自組織の権限グループ付与は成立すべきである")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("CMP-048: 重複したグループIDは1行だけ割り当てる")
    void cmp048_duplicateGroupIdsCreateOneAssignment() {
        Long orgId = persistOrganization();
        Long admin = persistActiveUser();
        grantOrgRole(admin, orgId, "ADMIN", 2);
        Long targetUser = persistActiveUser();
        grantOrgRole(targetUser, orgId, "DEPUTY_ADMIN", 3);
        Long groupId = persistOrgPermissionGroup(orgId);
        flushClear();

        permissionGroupService.assignUserPermissionGroups(targetUser, orgId, "ORGANIZATION",
                new UserPermissionGroupAssignRequest(List.of(groupId, groupId)), admin);
        flushClear();

        Long assigned = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_permission_groups WHERE user_id = :uid AND group_id = :gid")
                .setParameter("uid", targetUser)
                .setParameter("gid", groupId)
                .getSingleResult()).longValue();
        assertThat(assigned).isEqualTo(1L);
    }

    // =====================================================================
    // AC-13: 越境付与の拒否が既存の正当な割当を巻き添えにしない（番人）
    // =====================================================================

    /**
     * AC-13【番人】: 越境グループ ID を含む付与要求が拒否されたあと、
     * 被害者となる利用者の<b>既存の正当な割当が残っていること</b>。
     *
     * <p><b>この番人が守るもの</b>:
     * {@link PermissionGroupService#assignUserPermissionGroups} は
     * 「先に {@code deleteByUserIdAndGroupIdIn} で当該スコープの既存割当を全消しし、
     * その後のループで越境 ID を検知して throw する」構造になっている。
     * 現状はメソッドの {@code @Transactional} が例外でロールバックするため実害は無い。
     * しかし将来トランザクション境界が崩れると
     * （{@code REQUIRES_NEW} の混入、{@code noRollbackFor} の追加、呼び出し元での例外握り潰し等）、
     * <b>「越境付与を試みるだけで被害者の既存権限が全消しされる」</b>という壊れ方をする。
     * AC-11 は「越境 ID の行が作られていないこと」しか見ておらず、この軸を覆っていない。</p>
     *
     * <p><b>検証の作法</b>: 本テストだけはクラス既定のテストトランザクションを使わない
     * （{@link Propagation#NOT_SUPPORTED}）。テストトランザクションに参加した状態では
     * サービスの例外はロールバック<b>予約</b>にしかならず、削除がその場では取り消されないため、
     * 本番の挙動（呼び出しごとに独立したトランザクションが実際にロールバックする）を再現できない。</p>
     *
     * <p><b>サービス呼び出しを外側のトランザクションで包んではならない</b>:
     * {@link TransactionTemplate} で包むと、ロールバックしているのは外側のテンプレートであって
     * サービス自身のトランザクション境界ではなくなる。その形では
     * {@code @Transactional(noRollbackFor = BusinessException.class)} のような境界の破壊を
     * 入れても番人が緑のまま素通りする（本テスト設置時に実測で確認済み）。
     * よってフィクスチャ投入と結果の読み直しだけを {@link TransactionTemplate} で包み、
     * <b>サービスは裸で呼ぶ</b>（サービス自身の {@code @Transactional} が唯一の境界になる）。</p>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("AC-13【番人】: 越境グループIDを含む付与要求が拒否されても既存の正当な割当は巻き添えにならない")
    void ac13_番人_越境付与の拒否で既存割当は巻き添えにならない() {
        // 1) フィクスチャ投入（独立したトランザクションでコミットする）
        Long[] ids = transactionTemplate.execute(status -> {
            Long orgA = persistOrganization();
            Long orgB = persistOrganization();
            Long adminOfA = persistActiveUser();
            grantOrgRole(adminOfA, orgA, "ADMIN", 2);
            Long targetUser = persistActiveUser();
            grantOrgRole(targetUser, orgA, "DEPUTY_ADMIN", 3);
            Long groupOfA = persistOrgPermissionGroup(orgA);
            Long groupOfB = persistOrgPermissionGroup(orgB);
            // 被害者は既に自組織の正当な権限束を持っている
            assignGroupToUser(targetUser, groupOfA);
            em.flush();
            return new Long[]{orgA, adminOfA, targetUser, groupOfA, groupOfB};
        });
        Long orgA = ids[0];
        Long adminOfA = ids[1];
        Long targetUser = ids[2];
        Long groupOfA = ids[3];
        Long groupOfB = ids[4];

        assertThat(countAssignment(targetUser, groupOfA))
                .as("前提: 越境要求の前に、自組織の正当な割当が 1 件存在すること")
                .isEqualTo(1L);

        // 2) 越境グループ ID のみを含む付与要求。
        //    外側にトランザクションを張らずに裸で呼ぶ（サービス自身の境界だけが働く状態にする）。
        UserPermissionGroupAssignRequest req = new UserPermissionGroupAssignRequest(List.of(groupOfB));
        assertThatThrownBy(() -> permissionGroupService.assignUserPermissionGroups(
                targetUser, orgA, "ORGANIZATION", req, adminOfA))
                .as("他組織の権限グループ ID を含む付与要求は拒否されるべきである")
                .isInstanceOf(BusinessException.class);

        // 3) 実 DB を読み直して被害の有無を確認する
        assertThat(countAssignment(targetUser, groupOfA))
                .as("拒否された越境付与要求の巻き添えで、既存の正当な割当が消えてはならない"
                        + "（消えていればトランザクション境界が崩れている）")
                .isEqualTo(1L);
        assertThat(countAssignment(targetUser, groupOfB))
                .as("拒否された付与要求で越境グループの割当行が作られてはならない")
                .isZero();
    }

    /** 独立したトランザクションで実 DB の割当行数を数える（1 次キャッシュを跨ぐ）。 */
    private long countAssignment(Long userId, Long groupId) {
        Long count = transactionTemplate.execute(status -> {
            em.clear();
            return ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM user_permission_groups "
                                    + "WHERE user_id = :uid AND group_id = :gid")
                    .setParameter("uid", userId)
                    .setParameter("gid", groupId)
                    .getSingleResult()).longValue();
        });
        return count == null ? 0L : count;
    }

    /** 実機の改名500回帰: 同じ権限の再登録でもコミットでき、他スコープを巻き込まない。 */
    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("CMP-261011-0035: 同じ権限を保持した改名をコミットし、紐付けは1件・他スコープは不変")
    void 同じ権限を保持した改名が実MySQLで成功する(ScopeType scopeType) {
        UpdateFixture fixture = persistUpdateFixture(scopeType);
        try {
            assertThatCode(() -> permissionGroupService.updatePermissionGroup(fixture.groupId(),
                    new PermissionGroupRequest("改名後", "MEMBER", List.of(fixture.permissionId())),
                    fixture.adminId())).doesNotThrowAnyException();

            assertUpdatedGroup(fixture.groupId(), "改名後", List.of(fixture.permissionId()));
            assertUpdatedGroup(fixture.otherGroupId(), fixture.otherName(), List.of(fixture.permissionId()));
        } finally {
            cleanupUpdateFixture(fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("CMP-261011-0035: 一部の権限を保持して差し替えても重複せず、削除対象だけが消える")
    void 一部保持する権限差替えが実MySQLで成功する(ScopeType scopeType) {
        UpdateFixture fixture = persistUpdateFixture(scopeType);
        try {
            Long[] replacement = transactionTemplate.execute(status -> {
                Long oldPermission = persistPermission("MANAGE_FILES");
                Long newPermission = persistPermission("MANAGE_POSTS");
                addPermissionToGroup(fixture.groupId(), oldPermission);
                return new Long[]{oldPermission, newPermission};
            });
            assertThatCode(() -> permissionGroupService.updatePermissionGroup(fixture.groupId(),
                    new PermissionGroupRequest("権限差替え後", "MEMBER",
                            List.of(fixture.permissionId(), replacement[1])), fixture.adminId()))
                    .doesNotThrowAnyException();

            assertUpdatedGroup(fixture.groupId(), "権限差替え後",
                    List.of(fixture.permissionId(), replacement[1]));
            assertUpdatedGroup(fixture.otherGroupId(), fixture.otherName(), List.of(fixture.permissionId()));
        } finally {
            cleanupUpdateFixture(fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("CMP-261011-0035: 更新後に同一TXをロールバックすると旧名と旧権限が残る")
    void 削除確定後も更新全体のロールバックが保たれる(ScopeType scopeType) {
        UpdateFixture fixture = persistUpdateFixture(scopeType);
        try {
            // flushはコミットではない。同じ業務TXが失敗すれば削除・再登録・改名を一緒に戻す。
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                permissionGroupService.updatePermissionGroup(fixture.groupId(),
                        new PermissionGroupRequest("巻き戻す改名", "MEMBER", List.of(fixture.permissionId())),
                        fixture.adminId());
                throw new IllegalStateException("更新後のロールバック");
            })).isInstanceOf(IllegalStateException.class).hasMessage("更新後のロールバック");

            assertUpdatedGroup(fixture.groupId(), fixture.originalName(), List.of(fixture.permissionId()));
            assertUpdatedGroup(fixture.otherGroupId(), fixture.otherName(), List.of(fixture.permissionId()));
        } finally {
            cleanupUpdateFixture(fixture);
        }
    }

    /** 独立Testcontainers内の新規groupだけを作る。利用者へのグループ割当は行わない。 */
    private UpdateFixture persistUpdateFixture(ScopeType scopeType) {
        prepareUpdateUniqueConstraint();
        return transactionTemplate.execute(status -> {
            Long scopeId = persistUpdateScope(scopeType);
            Long otherScopeId = persistUpdateScope(scopeType);
            Long adminId = persistActiveUser();
            em.persist(UserRoleEntity.builder().userId(adminId)
                    .roleId(persistRoleIfNeeded("ADMIN", 2))
                    .teamId(scopeType == ScopeType.TEAM ? scopeId : null)
                    .organizationId(scopeType == ScopeType.ORGANIZATION ? scopeId : null).build());
            em.persist(MembershipEntity.builder().userId(adminId).scopeType(scopeType)
                    .scopeId(scopeId).roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
            Long permissionId = persistPermission("MANAGE_SCHEDULES");
            PermissionGroupEntity group = persistUpdateGroup(scopeType, scopeId);
            PermissionGroupEntity otherGroup = persistUpdateGroup(scopeType, otherScopeId);
            addPermissionToGroup(group.getId(), permissionId);
            addPermissionToGroup(otherGroup.getId(), permissionId);
            return new UpdateFixture(scopeType, scopeId, otherScopeId, adminId, group.getId(),
                    otherGroup.getId(), permissionId, group.getName(), otherGroup.getName());
        });
    }

    /**
     * ddl-auto=create は ID 参照の中間 Entity の一意制約を生成しないため、更新回帰の前提だけに
     * 正式 V2.008__create_permission_group_permissions_table.sql の uq_pgp を再現する。
     * 対象は再利用しない独立 Testcontainer のみで、実機 runtime の 13381 へは接続しない。
     */
    private void prepareUpdateUniqueConstraint() {
        assertThat(MYSQL.getMappedPort(3306)).isNotEqualTo(13381);
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            assertThat(connection.getMetaData().getURL()).isEqualTo(MYSQL.getJdbcUrl());
            return null;
        });
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(MYSQL.getDatabaseName());
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'permission_group_permissions' "
                + "AND index_name = 'uq_pgp'", Integer.class);
        if (existing == 0) {
            jdbcTemplate.execute("ALTER TABLE permission_group_permissions "
                    + "ADD CONSTRAINT uq_pgp UNIQUE (group_id, permission_id)");
        }
        System.out.printf("PG_UPDATE_TEST_DATABASE container=%s host=%s port=%d catalog=%s%n",
                MYSQL.getContainerId(), MYSQL.getHost(), MYSQL.getMappedPort(3306), MYSQL.getDatabaseName());
    }

    private Long persistUpdateScope(ScopeType scopeType) {
        if (scopeType == ScopeType.ORGANIZATION) return persistOrganization();
        int n = nextSeq();
        TeamEntity team = TeamEntity.builder().slug("pg-update-team-" + n).name("権限更新チーム" + n)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build();
        em.persist(team);
        em.flush();
        return team.getId();
    }

    private PermissionGroupEntity persistUpdateGroup(ScopeType scopeType, Long scopeId) {
        PermissionGroupEntity group = PermissionGroupEntity.builder().name("権限更新束" + nextSeq())
                .targetRole(PermissionGroupEntity.TargetRole.MEMBER)
                .teamId(scopeType == ScopeType.TEAM ? scopeId : null)
                .organizationId(scopeType == ScopeType.ORGANIZATION ? scopeId : null).build();
        em.persist(group);
        em.flush();
        return group;
    }

    /** 更新TXと別の読取TXで一次キャッシュを捨て、名前・対象ロール・重複のない紐付けを再確認する。 */
    private void assertUpdatedGroup(Long groupId, String name, List<Long> permissionIds) {
        transactionTemplate.executeWithoutResult(status -> {
            em.clear();
            PermissionGroupEntity group = em.find(PermissionGroupEntity.class, groupId);
            assertThat(group.getName()).isEqualTo(name);
            assertThat(group.getTargetRole()).isEqualTo(PermissionGroupEntity.TargetRole.MEMBER);
            List<Long> actual = em.createQuery("select p.permissionId from PermissionGroupPermissionEntity p "
                            + "where p.groupId = :groupId", Long.class)
                    .setParameter("groupId", groupId).getResultList();
            assertThat(actual).containsExactlyInAnyOrderElementsOf(permissionIds);
        });
    }

    /** コミットした自分のfixture行だけを片付ける。共通の権限・ロールmasterは保持する。 */
    private void cleanupUpdateFixture(UpdateFixture fixture) {
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("delete from PermissionGroupPermissionEntity p where p.groupId in :groupIds")
                    .setParameter("groupIds", List.of(fixture.groupId(), fixture.otherGroupId())).executeUpdate();
            em.remove(em.find(PermissionGroupEntity.class, fixture.groupId()));
            em.remove(em.find(PermissionGroupEntity.class, fixture.otherGroupId()));
            em.createQuery("delete from UserRoleEntity r where r.userId = :userId")
                    .setParameter("userId", fixture.adminId()).executeUpdate();
            em.createQuery("delete from MembershipEntity m where m.userId = :userId")
                    .setParameter("userId", fixture.adminId()).executeUpdate();
            em.remove(em.find(UserEntity.class, fixture.adminId()));
            Class<?> scopeClass = fixture.scopeType() == ScopeType.TEAM ? TeamEntity.class : OrganizationEntity.class;
            em.remove(em.find(scopeClass, fixture.scopeId()));
            em.remove(em.find(scopeClass, fixture.otherScopeId()));
        });
    }

    private record UpdateFixture(ScopeType scopeType, Long scopeId, Long otherScopeId, Long adminId,
                                 Long groupId, Long otherGroupId, Long permissionId,
                                 String originalName, String otherName) {
    }

    // =====================================================================
    // AC-12: 宛先解決の SQL 本数が受信者数に比例しない（N+1 非退行）
    // =====================================================================

    /**
     * AC-12: 宛先解決が発行する SQL 本数が、宛先人数に比例して増えないこと。
     *
     * <p>計測は既存の {@link SqlIntentCounter}（{@code application-test.yml} の
     * {@code hibernate.session_factory.statement_inspector} として登録済み）で行う。</p>
     *
     * <p><b>計測機構の生存証明</b>: 「0 件だから上限以下」という偽の緑を作らないため、
     * 本テストはまず {@code SqlIntentCounter.totalCount()} が正の値であること
     * （＝ inspector が実際に SQL を捕捉していること）を assert する。
     * これが 0 なら計測機構が死んでいるので、上限比較そのものが無意味になる。</p>
     *
     * <p><b>判定軸</b>: 絶対本数の上限を新たに決め打ちするのではなく、
     * 宛先 1 名の場合と 10 名の場合で SQL 本数が<b>変わらない</b>ことを見る。
     * ループ内クエリが混ざれば必ず差が出る。</p>
     */
    @Test
    @DisplayName("AC-12: 宛先解決のSQL本数は宛先人数に比例しない（計測機構の生存証明つき）")
    void ac12_宛先解決のSQL本数は宛先人数に比例しない() {
        Long smallOrg = persistOrganization();
        Long largeOrg = persistOrganization();
        Long budgetAdminPermissionId = persistPermission(BUDGET_ADMIN);

        // 宛先 1 名の組織
        Long soloAdmin = persistActiveUser();
        grantOrgRole(soloAdmin, smallOrg, "ADMIN", 2);

        // 宛先 10 名の組織（ADMIN 5 名 + 権限グループ経由の BUDGET_ADMIN 保有者 5 名）。
        // 後者は GUEST ロールにして、権限グループ経路でしか宛先に入らないようにする。
        Long largeGroupId = persistOrgPermissionGroup(largeOrg, PermissionGroupEntity.TargetRole.MEMBER);
        addPermissionToGroup(largeGroupId, budgetAdminPermissionId);
        for (int i = 0; i < 5; i++) {
            Long admin = persistActiveUser();
            grantOrgRole(admin, largeOrg, "ADMIN", 2);
            Long holder = persistActiveUser();
            addMembership(holder, largeOrg, RoleKind.MEMBER);
            assignGroupToUser(holder, largeGroupId);
        }
        flushClear();

        SqlIntentCounter.reset();
        List<Long> smallRecipients = resolveRecipients(smallOrg);
        int smallSqlCount = SqlIntentCounter.totalCount();

        SqlIntentCounter.reset();
        List<Long> largeRecipients = resolveRecipients(largeOrg);
        int largeSqlCount = SqlIntentCounter.totalCount();

        assertThat(smallSqlCount)
                .as("計測機構（SqlIntentCounter）が生きていること。0 なら inspector 未登録であり、"
                        + "以降の上限比較はすべて無意味である")
                .isPositive();
        assertThat(smallRecipients).as("宛先 1 名の組織では 1 名が返るべきである").hasSize(1);
        assertThat(largeRecipients).as("宛先 10 名の組織では 10 名が返るべきである").hasSize(10);
        assertThat(largeSqlCount)
                .as("宛先が 1 名から 10 名に増えても宛先解決の SQL 本数は増えてはならない（N+1 非退行）")
                .isEqualTo(smallSqlCount);
    }
}
