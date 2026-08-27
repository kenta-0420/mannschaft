package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Issue #2797 / CMP-040 の試練（テスト先行）: {@code UserRoleRepository} の権限グループ経由
 * native クエリ 3 本が実スキーマと食い違っている欠陥の受け入れテスト。
 *
 * <p><b>欠陥</b>: 下記 3 本の native クエリが、実在しない列を参照している。</p>
 * <ul>
 *   <li>{@link UserRoleRepository#findUserIdsByOrganizationIdAndPermissionName(Long, String)}</li>
 *   <li>{@link UserRoleRepository#existsDeputyAdminWithPermissionInOrganization(Long, Long, String)}</li>
 *   <li>{@link UserRoleRepository#findDeputyAdminUserIdsByTeamIdAndPermission(Long, String)}</li>
 * </ul>
 *
 * <p>誤参照の内訳:</p>
 * <ul>
 *   <li>{@code permission_group_permissions.permission_group_id} — 実列は {@code group_id}</li>
 *   <li>{@code user_permission_groups.permission_group_id} — 実列は {@code group_id}</li>
 *   <li>{@code user_permission_groups.organization_id} — <b>列自体が存在しない</b></li>
 * </ul>
 *
 * <p><b>正しい姿</b>: 組織／チームのスコープは {@code permission_groups} 側が
 * {@code organization_id} / {@code team_id}（CHECK 制約 {@code chk_permission_groups_scope} により
 * 排他的に片方必須）として既に保持している。したがって {@code permission_groups} を JOIN し、
 * グループ側のスコープ列で絞るのが正当であり、DDL 追加は不要である。
 * また {@code permission_groups} は {@code deleted_at} による論理削除だが、
 * {@link PermissionGroupEntity} の {@code @SQLRestriction} は native クエリには効かないため、
 * SQL 側で明示的に {@code deleted_at IS NULL} を課す必要がある。</p>
 *
 * <p><b>フィクスチャ方針</b>: 権限グループ経由の付与は
 * {@code permission_groups} + {@code permission_group_permissions} + {@code user_permission_groups}
 * の 3 表を実際に永続化して組み立てる。ロール既定権限（{@code role_permissions}）経由だけで
 * 組み立てると、壊れている権限グループ経路を一度も踏まないまま green になるためである。
 * {@code permission_groups} の {@code team_id} / {@code organization_id} は必ず片方のみを詰め、
 * 本番で成立しえない行（両方詰め・両方 NULL）は作らない。</p>
 *
 * <p>test profile は {@code ddl-auto: create} かつ Flyway 無効のため、権限・ロール・組織・
 * ユーザーはすべて本テスト内で永続化する。</p>
 */
@Transactional
@DisplayName("Issue #2797: 権限グループ経由の native クエリ 3 本が実スキーマと一致し、スコープ境界を守る")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRolePermissionGroupNativeQueryTest extends AbstractMySqlIntegrationTest {

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 本テスト専用のチーム ID 採番（teams 行は参照されないため採番のみで足りる）。 */
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long nextTeamId() {
        return 797_000L + TEAM_SEQ.incrementAndGet();
    }

    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("i2797-org-" + n)
                .name("2797テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        em.flush();
        return org.getId();
    }

    private UserEntity persistUserEntity(UserEntity.UserStatus status) {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("i2797-pg-" + n + "@example.com")
                .lastName("権限")
                .firstName("グループ" + n)
                .displayName("権限グループ" + n)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user;
    }

    private Long persistActiveUser() {
        return persistUserEntity(UserEntity.UserStatus.ACTIVE).getId();
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
    // 権限フィクスチャ（Issue #2797 コメントから移送）
    // ---------------------------------------------------------------------

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

    private void grantRolePermission(Long roleId, Long permissionId) {
        RolePermissionEntity rp = RolePermissionEntity.builder()
                .roleId(roleId)
                .permissionId(permissionId)
                .isDefault(true)
                .build();
        em.persist(rp);
        em.flush();
    }

    // ---------------------------------------------------------------------
    // 権限グループフィクスチャ（3 表を実際に永続化する）
    // ---------------------------------------------------------------------

    /**
     * 組織スコープの権限グループを作る。
     *
     * <p>{@code team_id} は詰めない（CHECK 制約 {@code chk_permission_groups_scope} により
     * 組織／チームは排他であり、両方詰めた行は本番で成立しえない）。</p>
     */
    private Long persistOrgPermissionGroup(Long organizationId) {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .organizationId(organizationId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .name("2797組織権限束" + nextSeq())
                .build();
        em.persist(group);
        em.flush();
        return group.getId();
    }

    /** チームスコープの権限グループを作る（{@code organization_id} は詰めない）。 */
    private Long persistTeamPermissionGroup(Long teamId) {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .teamId(teamId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .name("2797チーム権限束" + nextSeq())
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

    /**
     * 権限グループを論理削除する。
     *
     * <p>{@link PermissionGroupEntity} は {@code @SQLRestriction("deleted_at IS NULL")} を持つが
     * native クエリには効かないため、SQL 側の {@code deleted_at IS NULL} 条件が必要である
     * ことを検証するために、native UPDATE で直接論理削除する。</p>
     */
    private void softDeletePermissionGroup(Long groupId) {
        em.createNativeQuery("UPDATE permission_groups SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", groupId)
                .executeUpdate();
    }

    /** 「権限グループ経由で権限を持つ組織 DEPUTY_ADMIN」を一括で用意する。 */
    private Long deputyAdminWithGroupPermissionInOrg(Long organizationId, Long permissionId) {
        Long user = persistActiveUser();
        grantOrgRole(user, organizationId, "DEPUTY_ADMIN", 3);
        Long groupId = persistOrgPermissionGroup(organizationId);
        addPermissionToGroup(groupId, permissionId);
        assignGroupToUser(user, groupId);
        return user;
    }

    // =====================================================================
    // AC-1: 3 本の native クエリが実 DB で例外なく実行できる
    // =====================================================================

    /**
     * AC-1: 3 本の native クエリが実 DB に対して例外を投げずに実行できること。
     *
     * <p>現状はいずれも {@code SQLSyntaxErrorException: Unknown column ...} を根本原因とする
     * 例外で失敗する。実 DB で走らせない限り検出できないため、モックは用いない。</p>
     */
    @Test
    @DisplayName("AC-1: 権限グループ経由の native クエリ3本が実DBで例外なく実行できる")
    void ac1_3本のnativeクエリが実DBで例外なく実行できる() {
        Long orgId = persistOrganization(null);
        Long teamId = nextTeamId();
        String permissionName = "I2797_SMOKE_PERMISSION";
        persistPermission(permissionName);
        Long user = persistActiveUser();
        grantOrgRole(user, orgId, "DEPUTY_ADMIN", 3);
        flushClear();

        assertThatCode(() -> userRoleRepository
                .findUserIdsByOrganizationIdAndPermissionName(orgId, permissionName))
                .as("findUserIdsByOrganizationIdAndPermissionName は実スキーマ上で実行可能であるべきである")
                .doesNotThrowAnyException();
        assertThatCode(() -> userRoleRepository
                .existsDeputyAdminWithPermissionInOrganization(user, orgId, permissionName))
                .as("existsDeputyAdminWithPermissionInOrganization は実スキーマ上で実行可能であるべきである")
                .doesNotThrowAnyException();
        assertThatCode(() -> userRoleRepository
                .findDeputyAdminUserIdsByTeamIdAndPermission(teamId, permissionName))
                .as("findDeputyAdminUserIdsByTeamIdAndPermission は実スキーマ上で実行可能であるべきである")
                .doesNotThrowAnyException();
    }

    // =====================================================================
    // AC-2: 組織越境の遮断
    // =====================================================================

    /**
     * AC-2: A 社の権限グループで権限を付与された者が、B 社の照会では返らないこと。
     *
     * <p>{@code user_permission_groups} には組織列が無いため、グループ側の
     * {@code permission_groups.organization_id} で絞らない限り、割当は全組織へ漏れる。
     * 「A 社で予算管理者に任命された者が B 社の予算警告通知を受け取る」ことになる。</p>
     */
    @Test
    @DisplayName("AC-2: A社の権限グループで付与された者はB社の照会では返らない")
    void ac2_権限グループ付与は組織を越境しない() {
        Long orgA = persistOrganization(null);
        Long orgB = persistOrganization(null);
        String permissionName = "I2797_CROSS_ORG_PERMISSION";
        Long permissionId = persistPermission(permissionName);

        Long userInA = deputyAdminWithGroupPermissionInOrg(orgA, permissionId);
        // 越境を検出可能にするため、当人は B 社にも DEPUTY_ADMIN として在籍させる
        // （B 社側では当該権限を一切付与していない）。
        grantOrgRole(userInA, orgB, "DEPUTY_ADMIN", 3);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgA, permissionName))
                .as("A 社の権限グループ付与は A 社の照会で返るべきである")
                .contains(userInA);
        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgB, permissionName))
                .as("A 社の権限グループ付与を B 社の照会へ漏らしてはならない")
                .doesNotContain(userInA);
    }

    // =====================================================================
    // AC-3: 権限を持たぬ者は返らない
    // =====================================================================

    /**
     * AC-3: 当該権限をいずれの経路でも持たない者が返らないこと。
     *
     * <p>権限グループには在籍しているが別の権限しか含まない者・
     * 権限グループに一切割り当てられていない者の双方を置く。</p>
     */
    @Test
    @DisplayName("AC-3: 当該権限を持たぬ者は権限保有者照会に含まれない")
    void ac3_権限を持たぬ者は返らない() {
        Long orgId = persistOrganization(null);
        String targetPermission = "I2797_TARGET_PERMISSION";
        String otherPermission = "I2797_OTHER_PERMISSION";
        Long targetPermissionId = persistPermission(targetPermission);
        Long otherPermissionId = persistPermission(otherPermission);

        Long holder = deputyAdminWithGroupPermissionInOrg(orgId, targetPermissionId);
        // 別権限しか含まないグループに割り当てられた者
        Long otherGroupUser = deputyAdminWithGroupPermissionInOrg(orgId, otherPermissionId);
        // 権限グループに一切割り当てられていない者
        Long noGroupUser = persistActiveUser();
        grantOrgRole(noGroupUser, orgId, "DEPUTY_ADMIN", 3);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgId, targetPermission))
                .as("当該権限を持つ者のみが返るべきである")
                .containsExactly(holder)
                .doesNotContain(otherGroupUser, noGroupUser);
    }

    // =====================================================================
    // AC-4: 【陽性対照】ロール既定権限の経路は従来どおり
    // =====================================================================

    /**
     * AC-4【陽性対照】: {@code role_permissions} 由来の権限を持つ役職者が従来どおり返ること。
     *
     * <p>権限グループ経路を直す過程で {@code role_permissions} 経路の母集団を
     * 縮めてしまう逆向きの回帰が起きないことを締める番人である。
     * （Issue #2797 コメントから移送した red テスト。）</p>
     */
    @Test
    @DisplayName("AC-4【陽性対照】: user_roles由来の役職者の権限評価は従来どおり")
    void ac4派生_陽性対照_userRoles由来の役職者の権限評価は不変() {
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
    // AC-5: memberships 専属の一般メンバーの権限も評価される
    // =====================================================================

    /**
     * AC-5: {@code findUserIdsByOrganizationIdAndPermissionName} が
     * {@code memberships} 専属の一般メンバーの権限を評価すること。
     *
     * <p>MEMBER ロールに既定付与された権限を持つ一般メンバーは、
     * {@code user_roles} に行が無いという理由だけで警告通知の宛先から落ちてはならない。
     * （Issue #2797 コメントから移送した red テスト。）</p>
     */
    @Test
    @DisplayName("AC-5: findUserIdsByOrganizationIdAndPermissionNameがmemberships専属メンバーの権限を評価する")
    void ac5派生_権限保有者照会がmemberships専属メンバーを評価する() {
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

    // =====================================================================
    // AC-6: 論理削除されたグループ経由の権限は評価されない
    // =====================================================================

    /**
     * AC-6: 論理削除された権限グループ経由の権限が評価されないこと。
     *
     * <p>{@code @SQLRestriction} は JPA 経路にしか効かず native クエリには効かないため、
     * SQL 側で {@code pg.deleted_at IS NULL} を明示しない限り「削除したはずの権限束」で
     * 権限が生き続ける。1 次キャッシュ越しの判定にならないよう {@code flushClear()} を跨いで検証する。</p>
     */
    @Test
    @DisplayName("AC-6: 論理削除された権限グループ経由の権限は評価されない")
    void ac6_論理削除された権限グループは評価されない() {
        Long orgId = persistOrganization(null);
        String permissionName = "I2797_DELETED_GROUP_PERMISSION";
        Long permissionId = persistPermission(permissionName);

        Long liveHolder = deputyAdminWithGroupPermissionInOrg(orgId, permissionId);

        Long deletedGroupUser = persistActiveUser();
        grantOrgRole(deletedGroupUser, orgId, "DEPUTY_ADMIN", 3);
        Long deletedGroupId = persistOrgPermissionGroup(orgId);
        addPermissionToGroup(deletedGroupId, permissionId);
        assignGroupToUser(deletedGroupUser, deletedGroupId);
        em.flush();
        softDeletePermissionGroup(deletedGroupId);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgId, permissionName))
                .as("論理削除済みの権限グループ経由の権限を生かしてはならない")
                .containsExactly(liveHolder)
                .doesNotContain(deletedGroupUser);
        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                deletedGroupUser, orgId, permissionName))
                .as("論理削除済みグループしか持たない DEPUTY_ADMIN に許可を出してはならない")
                .isFalse();
    }

    // =====================================================================
    // AC-7: 権限グループを持たぬ組織の照会は空配列
    // =====================================================================

    /**
     * AC-7: 権限グループを 1 つも持たない組織の照会が例外ではなく空リストを返すこと。
     *
     * <p>大多数の組織は権限グループを作っていない。ここが例外になると
     * 予算警告のバッチが組織単位で全滅する。</p>
     */
    @Test
    @DisplayName("AC-7: 権限グループを持たぬ組織の照会は例外ではなく空リストを返す")
    void ac7_権限グループ不在の組織は空リストを返す() {
        Long orgId = persistOrganization(null);
        String permissionName = "I2797_NO_GROUP_PERMISSION";
        persistPermission(permissionName);

        Long deputy = persistActiveUser();
        grantOrgRole(deputy, orgId, "DEPUTY_ADMIN", 3);
        flushClear();

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(orgId, permissionName))
                .as("権限グループ不在の組織は空リストを返すべきである（例外にしてはならない）")
                .isEmpty();
    }

    // =====================================================================
    // AC-9: existsDeputyAdminWithPermissionInOrganization の両側
    // =====================================================================

    /**
     * AC-9【許可側】: 権限グループ経由で権限を持つ組織 DEPUTY_ADMIN に対して true を返すこと。
     *
     * <p>本メソッドは {@code AccessControlService#checkAdminOrHasPermission} が
     * 直接呼ぶ認可の主経路である。例外になれば正当な DEPUTY_ADMIN が全員締め出される
     * （偽の拒否）。</p>
     */
    @Test
    @DisplayName("AC-9【許可側】: 権限グループ経由で権限を持つDEPUTY_ADMINにはtrueを返す")
    void ac9_許可側_権限グループ経由のDEPUTY_ADMINはtrue() {
        Long orgId = persistOrganization(null);
        String permissionName = "I2797_DEPUTY_GRANT_PERMISSION";
        Long permissionId = persistPermission(permissionName);

        Long deputy = deputyAdminWithGroupPermissionInOrg(orgId, permissionId);
        flushClear();

        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                deputy, orgId, permissionName))
                .as("権限グループ経由で権限を持つ DEPUTY_ADMIN には許可を出すべきである")
                .isTrue();
    }

    /**
     * AC-9【拒否側】: 権限を持たない者・他組織のグループしか持たない者に false を返すこと。
     *
     * <p>他組織のグループを割り当てられた DEPUTY_ADMIN に true を返すのが「偽の許可」であり、
     * {@code user_permission_groups} に組織列が無いまま素通しにすると必ずこの方向へ壊れる。</p>
     */
    @Test
    @DisplayName("AC-9【拒否側】: 権限なし・他組織グループのみ・非DEPUTY_ADMINにはfalseを返す")
    void ac9_拒否側_権限なしと他組織グループのみはfalse() {
        Long orgA = persistOrganization(null);
        Long orgB = persistOrganization(null);
        String permissionName = "I2797_DEPUTY_DENY_PERMISSION";
        Long permissionId = persistPermission(permissionName);

        // (a) 何も付与されていない DEPUTY_ADMIN
        Long bareDeputy = persistActiveUser();
        grantOrgRole(bareDeputy, orgA, "DEPUTY_ADMIN", 3);

        // (b) B 社のグループで権限を持ち、A 社にも DEPUTY_ADMIN として在籍する者
        Long crossOrgDeputy = deputyAdminWithGroupPermissionInOrg(orgB, permissionId);
        grantOrgRole(crossOrgDeputy, orgA, "DEPUTY_ADMIN", 3);

        // (c) A 社のグループで権限を持つが、ロールは MEMBER（DEPUTY_ADMIN ではない）
        Long member = persistActiveUser();
        addMembership(member, ScopeType.ORGANIZATION, orgA, RoleKind.MEMBER, null);
        Long orgAGroup = persistOrgPermissionGroup(orgA);
        addPermissionToGroup(orgAGroup, permissionId);
        assignGroupToUser(member, orgAGroup);
        flushClear();

        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                bareDeputy, orgA, permissionName))
                .as("権限を一切持たない DEPUTY_ADMIN に許可を出してはならない")
                .isFalse();
        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                crossOrgDeputy, orgA, permissionName))
                .as("B 社の権限グループ付与を A 社の許可判定へ持ち込んではならない")
                .isFalse();
        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                member, orgA, permissionName))
                .as("DEPUTY_ADMIN ロールを持たない一般メンバーに許可を出してはならない")
                .isFalse();
    }

    // =====================================================================
    // AC-10: findDeputyAdminUserIdsByTeamIdAndPermission のチーム境界
    // =====================================================================

    /**
     * AC-10: {@code findDeputyAdminUserIdsByTeamIdAndPermission} がチーム境界を越えないこと。
     *
     * <p>本メソッドは {@code user_permission_groups} をチーム条件なしで参照しているため、
     * 修正時に {@code permission_groups.team_id} で絞らないと、別チームで付与された権限が
     * 全チームへ漏れる（予約通知が無関係なチームの DEPUTY_ADMIN へ飛ぶ）。</p>
     */
    @Test
    @DisplayName("AC-10: findDeputyAdminUserIdsByTeamIdAndPermissionがチーム境界を越えない")
    void ac10_チーム権限グループはチーム境界を越えない() {
        Long teamA = nextTeamId();
        Long teamB = nextTeamId();
        String permissionName = "I2797_TEAM_PERMISSION";
        Long permissionId = persistPermission(permissionName);

        // teamA の権限グループで権限を持つ teamA の DEPUTY_ADMIN
        Long deputyInA = persistActiveUser();
        grantTeamRole(deputyInA, teamA, "DEPUTY_ADMIN", 3);
        Long groupA = persistTeamPermissionGroup(teamA);
        addPermissionToGroup(groupA, permissionId);
        assignGroupToUser(deputyInA, groupA);

        // teamB の権限グループで権限を持ち、teamA にも DEPUTY_ADMIN として在籍する者
        Long crossTeamDeputy = persistActiveUser();
        grantTeamRole(crossTeamDeputy, teamA, "DEPUTY_ADMIN", 3);
        grantTeamRole(crossTeamDeputy, teamB, "DEPUTY_ADMIN", 3);
        Long groupB = persistTeamPermissionGroup(teamB);
        addPermissionToGroup(groupB, permissionId);
        assignGroupToUser(crossTeamDeputy, groupB);
        flushClear();

        assertThat(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(teamA, permissionName))
                .as("teamA の権限グループ経由の DEPUTY_ADMIN のみが返るべきである")
                .containsExactly(deputyInA);
        assertThat(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(teamB, permissionName))
                .as("teamB の権限グループ経由の DEPUTY_ADMIN のみが返るべきである")
                .containsExactly(crossTeamDeputy);
    }
}
