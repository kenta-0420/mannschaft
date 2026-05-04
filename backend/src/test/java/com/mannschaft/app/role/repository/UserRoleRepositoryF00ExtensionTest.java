package com.mannschaft.app.role.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 ContentVisibilityResolver Phase A-3a {@link UserRoleRepository} 拡張結合テスト。
 *
 * <p>設計書 docs/features/F00_content_visibility_resolver.md §10.2 に対応する
 * 新規追加メソッド ({@code findByUserIdAndScopes} / {@code findByUserIdAndOrganizationIdIn})
 * の動作と、{@code MembershipBatchQueryService} が依存する {@code existsSystemAdminByUserId}
 * （A-3a 既存利用方針）の挙動を検証する。</p>
 *
 * <p>{@code findByUserIdAndScopes} については Hibernate Statistics により SQL 数 = 1
 * を確認する（バルク取得・N+1 回避が本拡張の主目的のため）。</p>
 *
 * <p>テスト構成は本リポジトリの規約に倣い、{@code @SpringBootTest} + Testcontainers MySQL +
 * {@code @Transactional} ロールバック方式とする（既存ネイティブクエリが MySQL 依存のため
 * H2 では再現できない）。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("UserRoleRepository F00 拡張 結合テスト")
class UserRoleRepositoryF00ExtensionTest {

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
    private UserRoleRepository repository;

    @PersistenceContext
    private EntityManager em;

    private Long userId;
    private Long otherUserId;
    private Long teamId1;
    private Long teamId2;
    private Long teamId3;
    private Long orgId1;
    private Long orgId2;
    private Long memberRoleId;
    private Long systemAdminRoleId;

    /**
     * 各テスト直前に最小限のテストデータを投入する。
     *
     * <p>roles 表は V2.014 で SYSTEM_ADMIN / MEMBER 等が seed 済みのため、ID を引いて再利用する。</p>
     */
    @BeforeEach
    void setUp() {
        // 1. ロール ID を seed から取得
        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        // 2. ユーザー 2 名作成
        userId = insertUser("f00.user@example.com", "山田", "太郎");
        otherUserId = insertUser("f00.other@example.com", "鈴木", "花子");

        // 3. 組織 2 つ作成
        orgId1 = insertOrganization("F00 テスト組織 A");
        orgId2 = insertOrganization("F00 テスト組織 B");

        // 4. チーム 3 つ作成
        teamId1 = insertTeam("F00 テストチーム 1");
        teamId2 = insertTeam("F00 テストチーム 2");
        teamId3 = insertTeam("F00 テストチーム 3");

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, created_at, updated_at) " +
                        "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', NOW(), NOW())")
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, " +
                        "supporter_enabled, version, created_at, updated_at) " +
                        "VALUES (:name, 'GENERAL', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
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

    /**
     * user_roles 行を挿入する。{@code teamId} と {@code organizationId} は片方のみ指定可。
     * SYSTEM_ADMIN 用に両方 null も許す。
     */
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

    /** Hibernate Statistics を取得しクリアして返す。 */
    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    // =========================================================================
    // findByUserIdAndScopes
    // =========================================================================

    @Nested
    @DisplayName("findByUserIdAndScopes — TEAM/ORG 混在のバルク所属取得")
    class FindByUserIdAndScopes {

        @Test
        @DisplayName("該当する所属のみを 1 SQL で返す")
        void 該当する所属のみを1SQLで返す() {
            // 配置: ユーザーは TEAM1 / TEAM2 / ORG1 に所属、TEAM3 と ORG2 には未所属
            insertUserRole(userId, memberRoleId, teamId1, null);
            insertUserRole(userId, memberRoleId, teamId2, null);
            insertUserRole(userId, memberRoleId, null, orgId1);
            // 別ユーザーのデータ（ノイズ）
            insertUserRole(otherUserId, memberRoleId, teamId1, null);
            insertUserRole(otherUserId, memberRoleId, null, orgId2);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndScopes(
                    userId,
                    Set.of(teamId1, teamId2, teamId3),
                    Set.of(orgId1, orgId2));

            // SQL 数 = 1（両集合非空のため findByUserIdAndScopesInternal のみ発行される）
            assertThat(stats.getPrepareStatementCount())
                    .as("findByUserIdAndScopes は 1 SQL で完結すべし")
                    .isEqualTo(1L);

            // 該当所属のみ 3 件（TEAM1 / TEAM2 / ORG1）
            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(UserRoleProjection::getUserId)
                    .containsOnly(userId);
            assertThat(result)
                    .extracting(UserRoleProjection::getTeamId)
                    .containsExactlyInAnyOrder(teamId1, teamId2, null);
            assertThat(result)
                    .extracting(UserRoleProjection::getOrganizationId)
                    .containsExactlyInAnyOrder(null, null, orgId1);
        }

        @Test
        @DisplayName("teamIds と organizationIds がともに空なら SQL を発行せず空 List を返す")
        void teamIds_organizationIds_両方空_SQL未発行で空リスト() {
            insertUserRole(userId, memberRoleId, teamId1, null);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndScopes(
                    userId, Collections.emptySet(), Collections.emptySet());

            assertThat(result).isEmpty();
            assertThat(stats.getPrepareStatementCount())
                    .as("両集合空なら SQL を発行しないこと")
                    .isZero();
        }

        @Test
        @DisplayName("teamIds が空・organizationIds 非空なら ORG 限定 1 SQL のみ発行する")
        void teamIds空_org非空_org側のみ1SQL() {
            insertUserRole(userId, memberRoleId, teamId1, null);
            insertUserRole(userId, memberRoleId, null, orgId1);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndScopes(
                    userId, Collections.emptySet(), Set.of(orgId1, orgId2));

            assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getOrganizationId()).isEqualTo(orgId1);
            assertThat(result.get(0).getTeamId()).isNull();
        }

        @Test
        @DisplayName("teamIds 非空・organizationIds 空なら TEAM 限定 1 SQL のみ発行する")
        void teamIds非空_org空_team側のみ1SQL() {
            insertUserRole(userId, memberRoleId, teamId1, null);
            insertUserRole(userId, memberRoleId, teamId2, null);
            insertUserRole(userId, memberRoleId, null, orgId1);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndScopes(
                    userId, Set.of(teamId1, teamId2, teamId3), Collections.emptySet());

            assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(UserRoleProjection::getTeamId)
                    .containsExactlyInAnyOrder(teamId1, teamId2);
        }

        @Test
        @DisplayName("Projection が roleId を正しく返す")
        void Projection_roleId取得() {
            insertUserRole(userId, memberRoleId, teamId1, null);
            em.flush();
            em.clear();

            List<UserRoleProjection> result = repository.findByUserIdAndScopes(
                    userId, Set.of(teamId1), Collections.emptySet());

            assertThat(result).hasSize(1);
            UserRoleProjection p = result.get(0);
            assertThat(p.getRoleId()).isEqualTo(memberRoleId);
            assertThat(p.getId()).isNotNull();
        }
    }

    // =========================================================================
    // findByUserIdAndOrganizationIdIn
    // =========================================================================

    @Nested
    @DisplayName("findByUserIdAndOrganizationIdIn — 親 ORG メンバーシップ取得")
    class FindByUserIdAndOrganizationIdIn {

        @Test
        @DisplayName("指定 ORG に紐づく所属（TEAM 配下含む）をすべて返す")
        void ORG配下のTEAM所属も含めて返す() {
            // ORG1 直下メンバー
            insertUserRole(userId, memberRoleId, null, orgId1);
            // ORG2 直下メンバー（取得対象外）
            insertUserRole(userId, memberRoleId, null, orgId2);
            // 別ユーザーのノイズ
            insertUserRole(otherUserId, memberRoleId, null, orgId1);
            em.flush();
            em.clear();

            List<UserRoleProjection> result = repository.findByUserIdAndOrganizationIdIn(
                    userId, Set.of(orgId1));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getOrganizationId()).isEqualTo(orgId1);
            assertThat(result.get(0).getUserId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("organizationIds が空なら SQL を発行せず空 List を返す")
        void organizationIds空_SQL未発行で空リスト() {
            insertUserRole(userId, memberRoleId, null, orgId1);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndOrganizationIdIn(
                    userId, Collections.emptySet());

            assertThat(result).isEmpty();
            assertThat(stats.getPrepareStatementCount())
                    .as("空集合なら SQL を発行しないこと")
                    .isZero();
        }

        @Test
        @DisplayName("複数 ORG を指定すると IN 句で 1 SQL に集約される")
        void 複数ORG_1SQL集約() {
            insertUserRole(userId, memberRoleId, null, orgId1);
            insertUserRole(userId, memberRoleId, null, orgId2);
            em.flush();
            em.clear();

            Statistics stats = statisticsCleared();

            List<UserRoleProjection> result = repository.findByUserIdAndOrganizationIdIn(
                    userId, Set.of(orgId1, orgId2));

            assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
            assertThat(result).hasSize(2);
        }
    }

    // =========================================================================
    // existsSystemAdminByUserId（A-3a 既存利用方針：挙動確認のみ）
    // =========================================================================

    @Nested
    @DisplayName("existsSystemAdminByUserId — 既存メソッド挙動確認（A-3a 新規追加なし）")
    class ExistsSystemAdminByUserId {

        @Test
        @DisplayName("SYSTEM_ADMIN ユーザーは正の値を返す")
        void SYSTEM_ADMINユーザーは正の値を返す() {
            // SYSTEM_ADMIN は team_id・organization_id ともに NULL
            insertUserRole(userId, systemAdminRoleId, null, null);
            em.flush();
            em.clear();

            long count = repository.existsSystemAdminByUserId(userId);

            assertThat(count).isPositive();
            assertThat(count > 0).isTrue();
        }

        @Test
        @DisplayName("一般ユーザーは 0 を返す")
        void 一般ユーザーは0を返す() {
            insertUserRole(userId, memberRoleId, teamId1, null);
            em.flush();
            em.clear();

            long count = repository.existsSystemAdminByUserId(userId);

            assertThat(count).isZero();
        }
    }
}
