package com.mannschaft.app.membership;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F00.5 メンバーシップ基盤の DDL 制約検証統合テスト。
 *
 * <p><b>方針</b>: Spring ApplicationContext を一切起動せず、Testcontainers MySQL に
 * Flyway マイグレを直接適用した本物のスキーマに対して INSERT を試みる。これにより:</p>
 *
 * <ul>
 *   <li>本番と同じ Flyway 由来の CHECK / UNIQUE 制約（部分 UNIQUE 含む）が DB に存在する状態で検証できる</li>
 *   <li>Spring TestContext Cache に登録されないため OOM 連鎖の懸念なし
 *       （{@link com.mannschaft.app.support.test.AbstractMySqlIntegrationTest} のドキュメント参照）</li>
 *   <li>{@code application-test.yml} の {@code ddl-auto=create-drop} と {@code flyway.enabled=false} の影響を受けない</li>
 * </ul>
 *
 * <p>機能テスト（Service ロジック・Repository 派生メソッドの SQL 生成等）は
 * {@code MembershipServiceTest}（モック）と Phase 3 で追加予定の Service 統合テストでカバーする。
 * 本テストはあくまで <b>DDL の制約発火検証</b> に責務を絞る。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §14.2</p>
 */
@DisplayName("F00.5 メンバーシップ基盤 DDL 制約検証")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipBasisIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_f005_basis_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false);

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startContainerAndMigrate() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return;
        }
        MYSQL.start();

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void stopContainer() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void cleanTables() {
        // 各テスト前に F00.5 の 3 表をクリア（FK 順）
        jdbc.update("DELETE FROM member_positions");
        jdbc.update("DELETE FROM memberships");
        jdbc.update("DELETE FROM positions");
    }

    @Test
    @DisplayName("DDL: memberships に INSERT / SELECT が成立する")
    void insertAndSelect() {
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                1L, "TEAM", 100L, "MEMBER");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE user_id = ?",
                Integer.class, 1L);
        assertThat(count).isEqualTo(1);

        String roleKind = jdbc.queryForObject(
                "SELECT role_kind FROM memberships WHERE user_id = ?",
                String.class, 1L);
        assertThat(roleKind).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("uq_memberships_active: 同一 user × scope のアクティブ 2 行目は拒否")
    void duplicateActiveRejected() {
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                2L, "TEAM", 101L, "MEMBER");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                2L, "TEAM", 101L, "MEMBER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("再加入: 退会済の旧行を残しつつ新行 INSERT が成立")
    void rejoinAfterLeave() {
        // 入会
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                3L, "TEAM", 102L, "MEMBER");

        // 退会（少し未来の時刻にして CHECK chk_memberships_period をクリア）
        jdbc.update(
                "UPDATE memberships SET left_at = DATE_ADD(NOW(), INTERVAL 1 SECOND), leave_reason = 'SELF' " +
                        "WHERE user_id = ? AND scope_id = ?",
                3L, 102L);

        // 再加入: joined_at を更に未来に。active_key は新行のみ立つ
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) " +
                        "VALUES (?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL 2 SECOND))",
                3L, "TEAM", 102L, "MEMBER");

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE user_id = ? AND scope_id = ?",
                Integer.class, 3L, 102L);
        assertThat(total).isEqualTo(2);

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE user_id = ? AND scope_id = ? AND left_at IS NULL",
                Integer.class, 3L, 102L);
        assertThat(active).isEqualTo(1);
    }

    @Test
    @DisplayName("chk_memberships_period: left_at < joined_at は CHECK で拒否")
    void periodInvertedRejected() {
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                4L, "TEAM", 103L, "MEMBER");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE memberships SET left_at = DATE_SUB(NOW(), INTERVAL 1 YEAR), leave_reason = 'OTHER' " +
                        "WHERE user_id = ? AND scope_id = ?",
                4L, 103L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_memberships_left_reason: left_at と leave_reason の片方だけは拒否")
    void leftReasonInconsistencyRejected() {
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                5L, "TEAM", 104L, "MEMBER");

        // left_at だけセットして leave_reason は NULL のまま → CHECK で拒否
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE memberships SET left_at = DATE_ADD(NOW(), INTERVAL 1 SECOND) " +
                        "WHERE user_id = ? AND scope_id = ?",
                5L, 104L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("positions: uq_positions_scope_name が同 scope での重複を拒否")
    void positionScopeNameUnique() {
        jdbc.update(
                "INSERT INTO positions (scope_type, scope_id, name, display_name) VALUES (?, ?, ?, ?)",
                "TEAM", 105L, "TREASURER", "会計係");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO positions (scope_type, scope_id, name, display_name) VALUES (?, ?, ?, ?)",
                "TEAM", 105L, "TREASURER", "会計係2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("chk_memberships_gdpr_masked: gdpr_masked_at NOT NULL のとき user_id IS NULL でなければ拒否")
    void gdprMaskedRequiresNullUserId() {
        jdbc.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at) VALUES (?, ?, ?, ?, NOW())",
                6L, "TEAM", 106L, "MEMBER");

        // user_id を残したまま gdpr_masked_at をセット → CHECK で拒否
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE memberships SET gdpr_masked_at = NOW() WHERE user_id = ? AND scope_id = ?",
                6L, 106L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
