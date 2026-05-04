package com.mannschaft.app.common.visibility;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 ContentVisibilityResolver Phase A-3b — {@link MembershipBatchQueryService} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * {@code snapshotForUser(...)} の SQL 数と返却スナップショットの内容を検証する。
 * 設計書 §10.2 / §11.6。</p>
 *
 * <p>セットアップは A-3a の {@code UserRoleRepositoryF00ExtensionTest} の方式を踏襲。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles を
 * 直接 INSERT する。</p>
 *
 * <p>SQL 数の上限値は設計書 D-14 / 任務書「SQL 数 ≦ 3」を踏まえつつ、本実装では
 * §11.6 の連鎖判定（非アクティブ親 ORG 抽出）と role_name 解決を加えた最大 5 SQL
 * になる場合がある。各シナリオで具体的な発行回数を assertThat で検証する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("MembershipBatchQueryService 結合テスト")
class MembershipBatchQueryServiceIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OOM 対策（既存 Repository テストパターン踏襲）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private MembershipBatchQueryService service;

    @PersistenceContext
    private EntityManager em;

    private Long userId;
    private Long otherUserId;
    private Long teamId1;
    private Long teamId2;
    private Long orgId1;
    private Long orgId2;
    private Long memberRoleId;
    private Long systemAdminRoleId;

    @BeforeEach
    void setUp() {
        // 1. ロールを直接投入（A-3a 同様、Flyway 無効環境前提）。
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        // 2. ユーザー
        userId = insertUser("a3b.user@example.com", "山田", "太郎");
        otherUserId = insertUser("a3b.other@example.com", "鈴木", "花子");

        // 3. 組織
        orgId1 = insertOrganization("A-3b 組織 A", false);
        orgId2 = insertOrganization("A-3b 組織 B (削除済)", true); // §11.6 連鎖検証用

        // 4. チーム
        teamId1 = insertTeam("A-3b チーム 1");
        teamId2 = insertTeam("A-3b チーム 2");

        // 5. team_org_memberships: TEAM1 → ORG1（ACTIVE）、TEAM2 → ORG2（ACTIVE、ただし ORG2 は削除済）
        insertTeamOrgMembership(teamId1, orgId1);
        insertTeamOrgMembership(teamId2, orgId2);

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users (" +
                        "email, last_name, first_name, display_name, status, " +
                        "is_searchable, handle_searchable, contact_approval_required, " +
                        "online_visibility, dm_receive_from, encryption_key_version, " +
                        "locale, timezone, reporting_restricted, follow_list_visibility, " +
                        "care_notification_enabled, offline_only, " +
                        "created_at, updated_at) " +
                        "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', " +
                        "1, 1, 1, " +
                        "'NOBODY', 'ANYONE', 1, " +
                        "'ja', 'Asia/Tokyo', 0, 'PUBLIC', " +
                        "1, 0, " +
                        "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, boolean deleted) {
        if (deleted) {
            em.createNativeQuery(
                    "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, " +
                            "supporter_enabled, version, deleted_at, created_at, updated_at) " +
                            "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), NOW())")
                    .setParameter("name", name)
                    .executeUpdate();
        } else {
            em.createNativeQuery(
                    "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, " +
                            "supporter_enabled, version, created_at, updated_at) " +
                            "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW())")
                    .setParameter("name", name)
                    .executeUpdate();
        }
        // @SQLRestriction("deleted_at IS NULL") で SELECT が引っかからないため、native 直接照会
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) " +
                        "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        // NOTE: invited_at は @Column(nullable = false) のため明示必須（A-3a と同パターン）
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) " +
                        "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) " +
                        "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("一般ユーザー × directScopes 単一 TEAM → 該当所属を返し SQL 数 ≦ 3")
    void 一般ユーザー_directScopes単一TEAM() {
        // 配置: ユーザーは TEAM1 に MEMBER として所属
        insertUserRole(userId, memberRoleId, teamId1, null);
        em.flush();
        em.clear();

        Statistics stats = statisticsCleared();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                userId,
                Set.of(new ScopeKey("TEAM", teamId1)),
                Collections.emptySet());

        // SQL 数: existsSystemAdmin (1) + findByUserIdAndScopes (1) + role_name 解決 (1) = 3
        assertThat(stats.getPrepareStatementCount())
                .as("directScopes 単一 TEAM の SQL 数は 3 以下であるべし")
                .isLessThanOrEqualTo(3L);

        assertThat(snapshot.isSystemAdmin()).isFalse();
        assertThat(snapshot.roleByScope())
                .containsEntry(new ScopeKey("TEAM", teamId1), "MEMBER");
        assertThat(snapshot.isMemberOf(new ScopeKey("TEAM", teamId1))).isTrue();
        assertThat(snapshot.hasRoleOrAbove(new ScopeKey("TEAM", teamId1), "MEMBER")).isTrue();
    }

    @Test
    @DisplayName("一般ユーザー × directScopes 複数 TEAM/ORG 混在 → 該当多件、SQL 数 ≦ 3")
    void 一般ユーザー_directScopes複数混在() {
        insertUserRole(userId, memberRoleId, teamId1, null);
        insertUserRole(userId, memberRoleId, null, orgId1);
        em.flush();
        em.clear();

        Statistics stats = statisticsCleared();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                userId,
                Set.of(new ScopeKey("TEAM", teamId1),
                        new ScopeKey("TEAM", teamId2),
                        new ScopeKey("ORGANIZATION", orgId1)),
                Collections.emptySet());

        assertThat(stats.getPrepareStatementCount())
                .as("directScopes 複数混在の SQL 数は 3 以下であるべし")
                .isLessThanOrEqualTo(3L);

        assertThat(snapshot.roleByScope())
                .hasSize(2)
                .containsEntry(new ScopeKey("TEAM", teamId1), "MEMBER")
                .containsEntry(new ScopeKey("ORGANIZATION", orgId1), "MEMBER");
        // teamId2 には所属していない
        assertThat(snapshot.isMemberOf(new ScopeKey("TEAM", teamId2))).isFalse();
    }

    @Test
    @DisplayName("SystemAdmin → forSystemAdmin() を返し SQL 1 回で完結")
    void SystemAdmin_早期return_SQL1回() {
        insertUserRole(userId, systemAdminRoleId, null, null);
        em.flush();
        em.clear();

        Statistics stats = statisticsCleared();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                userId,
                Set.of(new ScopeKey("TEAM", teamId1)),
                Set.of(new ScopeKey("TEAM", teamId1)));

        assertThat(stats.getPrepareStatementCount())
                .as("SystemAdmin は existsSystemAdminByUserId のみで完結すべし")
                .isEqualTo(1L);

        assertThat(snapshot.isSystemAdmin()).isTrue();
        // SystemAdmin は 全スコープに対して true を返す
        assertThat(snapshot.isMemberOf(new ScopeKey("TEAM", teamId1))).isTrue();
        assertThat(snapshot.isMemberOfParentOrg(new ScopeKey("TEAM", teamId1))).isTrue();
        assertThat(snapshot.hasRoleOrAbove(new ScopeKey("TEAM", teamId1), "ADMIN")).isTrue();
    }

    @Test
    @DisplayName("orgWideScopes 経由で親 ORG メンバーシップが取得できる")
    void orgWideScopes_親ORGメンバーシップ() {
        // ユーザーは ORG1 直下 MEMBER だが TEAM1 にはいない
        insertUserRole(userId, memberRoleId, null, orgId1);
        em.flush();
        em.clear();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                userId,
                Collections.emptySet(),
                Set.of(new ScopeKey("TEAM", teamId1)));

        // direct メンバーではない (TEAM1 への直接所属無し)
        assertThat(snapshot.isMemberOf(new ScopeKey("TEAM", teamId1))).isFalse();
        // 親 ORG (ORG1) のメンバーである → ORGANIZATION_WIDE 公開ならアクセス可
        assertThat(snapshot.parentOrgByScope())
                .containsEntry(new ScopeKey("TEAM", teamId1), orgId1);
        assertThat(snapshot.orgMemberOf())
                .contains(new ScopeKey("ORGANIZATION", orgId1));
        assertThat(snapshot.isMemberOfParentOrg(new ScopeKey("TEAM", teamId1))).isTrue();
        // ORG1 はアクティブなので非アクティブ集合には入らない
        assertThat(snapshot.isParentOrgInactive(new ScopeKey("TEAM", teamId1))).isFalse();
    }

    @Test
    @DisplayName("§11.6 連鎖ルール — 親 ORG が削除済なら suspendedOrgIds に含まれる")
    void s11_6_削除済親ORG() {
        // ユーザーは ORG2 (削除済) 直下 MEMBER
        insertUserRole(userId, memberRoleId, null, orgId2);
        em.flush();
        em.clear();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                userId,
                Collections.emptySet(),
                Set.of(new ScopeKey("TEAM", teamId2)));

        // TEAM2 → ORG2 (削除済) が parentOrgByScope に登録されている
        assertThat(snapshot.parentOrgByScope())
                .containsEntry(new ScopeKey("TEAM", teamId2), orgId2);
        // ORG2 は削除済 → suspendedOrgIds に含まれる
        assertThat(snapshot.suspendedOrgIds()).contains(orgId2);
        assertThat(snapshot.isParentOrgInactive(new ScopeKey("TEAM", teamId2))).isTrue();
    }

    @Test
    @DisplayName("匿名ユーザー (userId=null) は SQL を 1 回も発行しない")
    void 匿名ユーザー_SQL未発行() {
        Statistics stats = statisticsCleared();

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                null,
                Set.of(new ScopeKey("TEAM", teamId1)),
                Set.of(new ScopeKey("TEAM", teamId1)));

        assertThat(stats.getPrepareStatementCount())
                .as("匿名ユーザーは SQL を発行しないこと")
                .isZero();
        assertThat(snapshot.isSystemAdmin()).isFalse();
        assertThat(snapshot.roleByScope()).isEmpty();
    }
}
