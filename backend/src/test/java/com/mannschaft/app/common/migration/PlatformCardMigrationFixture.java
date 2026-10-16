package com.mannschaft.app.common.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F18.1 AC18のPLATFORM一意制約fixture。既存from-scratchコンテナに相乗りする。
 * 本物のmigration SQLを旧schemaのcloneへ実行し、既存行と履歴の保持を検査する。
 * 本番表は変更せず、fixtureの固定名テーブルだけをfinallyで片付ける。
 */
final class PlatformCardMigrationFixture {
    private static final String SUCCESS = "platform_card_migration_success";
    private static final String DUPLICATE = "platform_card_migration_duplicate";
    private static final String CHECKINS = "platform_card_migration_checkins";
    private static final String UNIQUE_NAME = "uq_member_cards_active_platform_user_id";

    private PlatformCardMigrationFixture() { }

    static void verify(Connection connection) throws Exception {
        assertThat(hasColumn(connection, "member_cards", "active_platform_user_id"))
                .as("全Flyway適用後にPLATFORM生成列が存在する").isTrue();
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V250.*__ensure_platform_card_unique.sql");
        assertThat(resources).hasSize(1);
        String sql = resources[0].getContentAsString(StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            cloneOldCards(statement, SUCCESS);
            cloneOldCards(statement, DUPLICATE);
            statement.execute("CREATE TABLE " + CHECKINS + " LIKE member_card_checkins");
            seedCard(connection, SUCCESS, 1, 101, "PLATFORM", null, "ACTIVE", false);
            seedCard(connection, SUCCESS, 2, 102, "PLATFORM", null, "SUSPENDED", false);
            seedCard(connection, SUCCESS, 3, 103, "PLATFORM", null, "REVOKED", false);
            seedCard(connection, SUCCESS, 4, 101, "PLATFORM", null, "REVOKED", true);
            seedCard(connection, SUCCESS, 5, 101, "TEAM", 201L, "ACTIVE", false);
            seedCard(connection, SUCCESS, 6, 101, "ORGANIZATION", 301L, "ACTIVE", false);
            statement.execute("INSERT INTO " + CHECKINS
                    + " (member_card_id, checkin_type, checked_in_at, location)"
                    + " VALUES (1, 'STAFF_SCAN', '2020-01-01 01:00:00', '旧受付')");
            List<String> before = snapshot(connection, SUCCESS);
            List<String> checkins = checkinSnapshot(connection);
            statement.execute(sql.replace("ALTER TABLE member_cards", "ALTER TABLE " + SUCCESS));
            assertThat(snapshot(connection, SUCCESS)).isEqualTo(before);
            assertThat(checkinSnapshot(connection)).isEqualTo(checkins);
            assertThatThrownBy(() -> seedCard(connection, SUCCESS, 7, 101, "PLATFORM", null, "ACTIVE", false))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> seedCard(connection, SUCCESS, 8, 102, "PLATFORM", null, "ACTIVE", false))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> seedCard(connection, SUCCESS, 9, 103, "PLATFORM", null, "ACTIVE", false))
                    .isInstanceOf(SQLException.class);
            seedCard(connection, SUCCESS, 10, 104, "PLATFORM", null, "ACTIVE", false);

            seedCard(connection, DUPLICATE, 1, 501, "PLATFORM", null, "ACTIVE", false);
            seedCard(connection, DUPLICATE, 2, 501, "PLATFORM", null, "SUSPENDED", false);
            List<String> duplicateBefore = snapshot(connection, DUPLICATE);
            assertThatThrownBy(() -> statement.execute(sql.replace("ALTER TABLE member_cards", "ALTER TABLE " + DUPLICATE)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Duplicate entry");
            assertThat(snapshot(connection, DUPLICATE)).isEqualTo(duplicateBefore);
            assertThat(hasColumn(connection, DUPLICATE, "active_platform_user_id")).isFalse();
        } finally {
            // 固定名のfixture表だけを対象にする。共有DBや他テスト表を消さない。
            try (Statement cleanup = connection.createStatement()) {
                cleanup.execute("DROP TABLE IF EXISTS " + CHECKINS);
                cleanup.execute("DROP TABLE IF EXISTS " + SUCCESS);
                cleanup.execute("DROP TABLE IF EXISTS " + DUPLICATE);
            }
        }
    }

    private static void cloneOldCards(Statement statement, String table) throws SQLException {
        statement.execute("CREATE TABLE " + table + " LIKE member_cards");
        statement.execute("ALTER TABLE " + table + " DROP INDEX " + UNIQUE_NAME + ", DROP COLUMN active_platform_user_id");
    }

    private static void seedCard(Connection connection, String table, long id, long userId, String scope,
                                 Long scopeId, String status, boolean deleted) throws SQLException {
        String sql = "INSERT INTO " + table + " (id,user_id,scope_type,scope_id,card_code,card_number,display_name,status,"
                + "qr_secret,issued_at,checkin_count,total_spend,created_at,updated_at,deleted_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,'2020-01-01 00:00:00',7,123.45,'2020-01-01 00:00:00','2020-01-02 00:00:00',?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, userId);
            statement.setString(3, scope);
            statement.setObject(4, scopeId);
            statement.setString(5, UUID.randomUUID().toString());
            statement.setString(6, "旧カード-" + id);
            statement.setString(7, "fixture表示名");
            statement.setString(8, status);
            statement.setString(9, "fixture-signing-secret");
            statement.setObject(10, deleted ? "2020-01-03 00:00:00" : null);
            statement.executeUpdate();
        }
    }

    private static List<String> snapshot(Connection connection, String table) throws SQLException {
        // 秘密や表示名を失敗ログへ出さず、全ての旧業務列をhashで比較する。
        String columns = "id,user_id,scope_type,COALESCE(scope_id,0),card_code,card_number,display_name,status,"
                + "issued_at,COALESCE(suspended_at,''),COALESCE(revoked_at,''),COALESCE(last_checkin_at,''),"
                + "checkin_count,total_spend,qr_secret,created_at,updated_at,COALESCE(deleted_at,'')";
        return queryStrings(connection, "SELECT SHA2(CONCAT_WS('|'," + columns + "),256) FROM " + table + " ORDER BY id");
    }

    private static List<String> checkinSnapshot(Connection connection) throws SQLException {
        return queryStrings(connection, "SELECT SHA2(CONCAT_WS('|',id,member_card_id,checkin_type,checked_in_at,location),256) FROM "
                + CHECKINS + " ORDER BY id");
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return result.next();
        }
    }
}
