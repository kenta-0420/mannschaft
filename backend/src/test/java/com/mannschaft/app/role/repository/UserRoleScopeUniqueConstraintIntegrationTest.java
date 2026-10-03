package com.mannschaft.app.role.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code user_roles} のスコープ一意制約 {@code uq_user_roles_user_scope(user_id, scope_key)} と、
 * ロール変更（{@code RoleService.changeRole} 等の delete→save）が依存するフラッシュ順序を検証する番人テスト。
 *
 * <h2>このテストが守るバグ（実機 E2E + general_log で実証済み）</h2>
 * <p>{@code user_roles.scope_key} は {@code organization_id} / {@code team_id} から導出される
 * <b>生成列（GENERATED ALWAYS ... STORED）</b>で、{@code (user_id, scope_key)} に
 * ユニーク制約 {@code uq_user_roles_user_scope} が張られている（{@code V2.006}）。</p>
 *
 * <p>同一スコープ内でロールを変更する処理は「旧行を delete → 同一スコープの新行を insert」を行うが、
 * Hibernate の write-behind は <b>INSERT を DELETE より先にフラッシュする</b>ことがある。
 * この順序だと、旧行がまだ DB に残ったまま同じ {@code (user_id, scope_key)} を挿入しようとして
 * ユニーク制約に違反し、{@code DuplicateKeyException} → ROLLBACK → API 500 になる。</p>
 *
 * <p>根治は delete 直後に {@code flush()} を挟んで <b>DELETE を先に確定</b>させること。
 * 本テストは実 MySQL に対して本番同一 DDL を適用し:</p>
 * <ul>
 *   <li><b>バグの機序</b>: INSERT（新ロール・同一スコープ）を DELETE（旧ロール）より先に発行すると
 *       ユニーク制約違反で失敗すること</li>
 *   <li><b>根治の効果</b>: DELETE → INSERT の順（{@code flush()} が保証する順序）なら成功し、
 *       最終的に当該スコープの行が新ロール 1 行だけになること</li>
 * </ul>
 * <p>を JDBC レベルで直接実証する。</p>
 *
 * <h2>なぜ Testcontainers の実 MySQL で検証するか</h2>
 * <p>共通の統合テスト環境（{@code application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作し、
 * スキーマは Entity 定義から生成される。{@code UserRoleEntity.scopeKey} は生成列指定も
 * ユニーク制約注釈も持たないため、その環境では {@code scope_key} 生成列も
 * {@code uq_user_roles_user_scope} 制約も存在せず、本バグを再現できない。
 * そこで {@code EventCheckinTicketCheckConstraintIntegrationTest} と同じく、実 MySQL 8.0 に
 * 本番同一 DDL（{@code V2.006} の生成列＋ユニーク制約）を JDBC で適用して検証する。</p>
 *
 * <p>クロスドメイン FK（users / roles / teams / organizations への参照）は本テストの関心外であり、
 * かつそれらの親テーブルを用意すると無関係な DDL を大量に持ち込むため、{@code user_roles} 単体の
 * DDL のみを忠実に再現する（FK は省略・生成列とユニーク制約は本番同一）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.role.repository.UserRoleScopeUniqueConstraintIntegrationTest#isDockerAvailable")
@DisplayName("user_roles スコープ一意制約・ロール変更フラッシュ順序テスト")
class UserRoleScopeUniqueConstraintIntegrationTest {

    /**
     * V2.006 の {@code user_roles} テーブル定義（生成列 {@code scope_key} ＋ ユニーク制約）。
     * クロスドメイン FK は本テストの関心外のため省略する。生成列とユニーク制約は本番と同一。
     */
    private static final String DDL_TABLE = """
            CREATE TABLE user_roles (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                user_id BIGINT UNSIGNED NOT NULL,
                role_id BIGINT UNSIGNED NOT NULL,
                team_id BIGINT UNSIGNED NULL,
                organization_id BIGINT UNSIGNED NULL,
                scope_key VARCHAR(100) GENERATED ALWAYS AS (
                    COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')
                ) STORED,
                granted_by BIGINT UNSIGNED NULL,
                created_at DATETIME NOT NULL,
                updated_at DATETIME NOT NULL,
                PRIMARY KEY (id),
                CONSTRAINT uq_user_roles_user_scope UNIQUE (user_id, scope_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final long USER_ID = 42L;
    private static final long ORG_ID = 7L;
    private static final long MEMBER_ROLE_ID = 101L;
    private static final long DEPUTY_ADMIN_ROLE_ID = 102L;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"));

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 各テストの冒頭で user_roles を作り直し、MEMBER ロールの行を 1 件投入する。 */
    private void recreateTableWithMemberRow(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_roles");
            st.execute(DDL_TABLE);
        }
        insertRole(conn, MEMBER_ROLE_ID);
    }

    /** 指定ロールの user_roles 行を ORG スコープで 1 件挿入する。 */
    private void insertRole(Connection conn, long roleId) throws SQLException {
        try (Statement ins = conn.createStatement()) {
            ins.executeUpdate(
                    "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                            + "VALUES (" + USER_ID + ", " + roleId + ", NULL, " + ORG_ID + ", NOW(), NOW())");
        }
    }

    /** 指定ロールの user_roles 行を ORG スコープから 1 件削除する。 */
    private void deleteRole(Connection conn, long roleId) throws SQLException {
        try (Statement del = conn.createStatement()) {
            del.executeUpdate(
                    "DELETE FROM user_roles WHERE user_id = " + USER_ID
                            + " AND organization_id = " + ORG_ID + " AND role_id = " + roleId);
        }
    }

    @Test
    @DisplayName("バグ機序_INSERTを先にDELETEを後にするとユニーク制約違反で失敗する")
    void insert先行delete後行はユニーク制約違反で失敗する() throws SQLException {
        // flush を挟まない Hibernate の write-behind（INSERT を DELETE より先に発行）を JDBC で再現する。
        // 旧 MEMBER 行が残ったまま同一スコープへ DEPUTY_ADMIN を挿入 → uq_user_roles_user_scope 違反。
        try (Connection conn = newConnection()) {
            recreateTableWithMemberRow(conn);

            assertThatThrownBy(() -> insertRole(conn, DEPUTY_ADMIN_ROLE_ID))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_user_roles_user_scope");
        }
    }

    @Test
    @DisplayName("根治_DELETEを先にINSERTを後にすればロール変更が成功し1行になる")
    void delete先行insert後行はロール変更に成功し新ロール1行になる() throws SQLException {
        // flush() が保証する DELETE→INSERT 順を JDBC で再現する。
        // MEMBER → DEPUTY_ADMIN 昇格が制約違反なく完了し、当該スコープに新ロール 1 行だけが残ることを確認する。
        try (Connection conn = newConnection()) {
            recreateTableWithMemberRow(conn);

            deleteRole(conn, MEMBER_ROLE_ID);
            insertRole(conn, DEPUTY_ADMIN_ROLE_ID);

            try (Statement sel = conn.createStatement();
                 ResultSet rs = sel.executeQuery(
                         "SELECT role_id, scope_key FROM user_roles "
                                 + "WHERE user_id = " + USER_ID + " AND organization_id = " + ORG_ID)) {
                assertThat(rs.next()).as("ロール変更後の行が 1 行存在する").isTrue();
                assertThat(rs.getLong("role_id"))
                        .as("ロールが DEPUTY_ADMIN に更新されている").isEqualTo(DEPUTY_ADMIN_ROLE_ID);
                assertThat(rs.getString("scope_key"))
                        .as("scope_key は ORG スコープを表す").isEqualTo("org:" + ORG_ID);
                assertThat(rs.next()).as("当該スコープの行は 1 行だけ（旧 MEMBER 行は消えている）").isFalse();
            }
        }
    }
}
