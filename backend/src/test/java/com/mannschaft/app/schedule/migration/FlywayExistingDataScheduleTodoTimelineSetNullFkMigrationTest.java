package com.mannschaft.app.schedule.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 第四陣A（Phase 4-A）の番人テスト。</b>
 *
 * <p>V109.001 で「他ドメインの実テーブル（schedules / todos / timeline_posts）を ON DELETE SET NULL で
 * 参照する群2＝構造参照のクロスドメインFK 8件」を撤廃only する。本テストが守る不変条件は、
 * 参照先テーブル単位（schedules 参照 / todos 参照 / timeline_posts 参照）で次を検証する:</p>
 * <ol>
 *   <li>V109.001 の直前（V107.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V109.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V109.001 で対象8FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
 *       孤児値を保持し続ける</b>（＝SET NULL 撤廃only の肝）。
 *       本番では参照先3テーブルはいずれも論理削除のみで物理削除されない（＝SET NULL 発火不能）が、
 *       撤廃後の SET-NULL 不在を直接実証するためテスト内では物理 DELETE で検証する。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.schedule.migration.FlywayExistingDataScheduleTodoTimelineSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ schedules/todos/timeline_posts 参照 SET NULL FK撤廃（V109.001）番人テスト")
class FlywayExistingDataScheduleTodoTimelineSetNullFkMigrationTest {

    private static final String PRE_V109_001_TARGET = "107.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase4a_setnull_fk")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

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

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** V107.001 まで適用してから戻すためのヘルパ（各テストは PER_CLASS の同一DBを共有するため最初に1度だけ実行）。 */
    private void migrateToPreTarget() {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V109_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V107.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V109.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで schedules / todos / timeline_posts の3参照先すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V109.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V107.001 時点での全8FK実在 → 3参照先ぶんのシード → 残り適用 → 全8FK撤廃 + 3参照先の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V107.001で全8FK実在_V109.001適用で全8FK撤廃_schedules/todos/timeline_posts物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV109_001が群2SET_NULL_FK8件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // ── given: V107.001 時点では対象8FKが全て実在すること（pre-state sanity）──
        final long schOwnerUserId;
        final long scheduleId;
        final long schTodoId;       // schedules を参照する todo（linked_schedule_id）
        final long todoOwnerUserId;
        final long subjectTodoId;   // todos を参照される側（related_todo_id 先）
        final long relatedMemoId;   // todos を参照する action_memo
        final long tlOwnerUserId;
        final long postId;          // timeline_posts を参照される側
        final long tlMemoId;        // timeline_posts を参照する action_memo
        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "activity_results", "fk_ar_schedule"))
                    .as("V107.001 時点では fk_ar_schedule が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "performance_records", "fk_pr_schedule"))
                    .as("V107.001 時点では fk_pr_schedule が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "todos", "fk_todos_schedules"))
                    .as("V107.001 時点では fk_todos_schedules が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "tournament_matches", "fk_tmatch_schedule"))
                    .as("V107.001 時点では fk_tmatch_schedule が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "action_memos", "fk_action_memos_related_todo"))
                    .as("V107.001 時点では fk_action_memos_related_todo が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_todos"))
                    .as("V107.001 時点では fk_schedules_todos が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "action_memos", "fk_action_memos_timeline_post"))
                    .as("V107.001 時点では fk_action_memos_timeline_post が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_timeline"))
                    .as("V107.001 時点では fk_pwp_timeline が実在すること").isTrue();

            // ── seed-1: schedules 参照（user スコープ schedule + linked_schedule_id 付き todo）──
            schOwnerUserId = insertUser(c, "p4a-sch-owner@example.com");
            scheduleId = insertUserSchedule(c, schOwnerUserId, "第四陣A schedules参照テスト予定");
            schTodoId = insertTodoLinkedSchedule(c, schOwnerUserId, scheduleId);

            // ── seed-2: todos 参照（PERSONAL todo + related_todo_id 付き action_memo）──
            todoOwnerUserId = insertUser(c, "p4a-todo-owner@example.com");
            subjectTodoId = insertPersonalTodo(c, todoOwnerUserId, "第四陣A todos参照テストTODO");
            relatedMemoId = insertActionMemoRelatedTodo(c, todoOwnerUserId, subjectTodoId);

            // ── seed-3: timeline_posts 参照（timeline_post + timeline_post_id 付き action_memo）──
            tlOwnerUserId = insertUser(c, "p4a-tl-owner@example.com");
            postId = insertTimelinePost(c, tlOwnerUserId);
            tlMemoId = insertActionMemoTimelinePost(c, tlOwnerUserId, postId);
        }

        // ── when: 残り（V109.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象8FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "activity_results", "fk_ar_schedule"))
                    .as("V109.001 で fk_ar_schedule が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "performance_records", "fk_pr_schedule"))
                    .as("V109.001 で fk_pr_schedule が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "todos", "fk_todos_schedules"))
                    .as("V109.001 で fk_todos_schedules が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "tournament_matches", "fk_tmatch_schedule"))
                    .as("V109.001 で fk_tmatch_schedule が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "action_memos", "fk_action_memos_related_todo"))
                    .as("V109.001 で fk_action_memos_related_todo が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_todos"))
                    .as("V109.001 で fk_schedules_todos が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "action_memos", "fk_action_memos_timeline_post"))
                    .as("V109.001 で fk_action_memos_timeline_post が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_timeline"))
                    .as("V109.001 で fk_pwp_timeline が撤廃されること").isFalse();

            // 既存の参照元行が全て生存していること
            assertThat(rowExists(c, "todos", schTodoId)).as("FK 撤廃後も todo 行が生存").isTrue();
            assertThat(rowExists(c, "action_memos", relatedMemoId)).as("FK 撤廃後も action_memo(todo) 行が生存").isTrue();
            assertThat(rowExists(c, "action_memos", tlMemoId)).as("FK 撤廃後も action_memo(timeline) 行が生存").isTrue();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が NULL 化されず孤児値を保持 ──

            // schedules 参照: schedule を物理削除 → todos.linked_schedule_id が孤児値保持
            deleteRow(c, "schedules", scheduleId);
            assertThat(rowExists(c, "schedules", scheduleId)).as("参照先 schedule が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "todos", schTodoId)).as("schedule 物理削除でも todo 行が生存").isTrue();
            assertThat(longColumn(c, "todos", "linked_schedule_id", schTodoId))
                    .as("linked_schedule_id が SET NULL されず孤児 schedule_id を保持すること").isEqualTo(scheduleId);

            // todos 参照: todo を物理削除 → action_memos.related_todo_id が孤児値保持
            deleteRow(c, "todos", subjectTodoId);
            assertThat(rowExists(c, "todos", subjectTodoId)).as("参照先 todo が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "action_memos", relatedMemoId)).as("todo 物理削除でも action_memo 行が生存").isTrue();
            assertThat(longColumn(c, "action_memos", "related_todo_id", relatedMemoId))
                    .as("related_todo_id が SET NULL されず孤児 todo_id を保持すること").isEqualTo(subjectTodoId);

            // timeline_posts 参照: post を物理削除（論理削除メソッドでなく実 DELETE）→ action_memos.timeline_post_id が孤児値保持
            deleteRow(c, "timeline_posts", postId);
            assertThat(rowExists(c, "timeline_posts", postId)).as("参照先 timeline_post が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "action_memos", tlMemoId)).as("timeline_post 物理削除でも action_memo 行が生存").isTrue();
            assertThat(longColumn(c, "action_memos", "timeline_post_id", tlMemoId))
                    .as("timeline_post_id が SET NULL されず孤児 post_id を保持すること").isEqualTo(postId);
        }
    }

    // ── seed helpers ───────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '第四', 'A郎', '第四A郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** user スコープの schedule をシード（ck_schedules_scope_xor: user_id のみ非NULL）。 */
    private long insertUserSchedule(Connection c, long userId, String title) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules
                    (team_id, organization_id, user_id, committee_id, title, start_at, event_type, status,
                     created_by, created_at, updated_at)
                VALUES (NULL, NULL, ?, NULL, ?, NOW() + INTERVAL 1 DAY, 'OTHER', 'SCHEDULED',
                        ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setLong(3, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** PERSONAL スコープの todo を linked_schedule_id 付きでシード。 */
    private long insertTodoLinkedSchedule(Connection c, long ownerUserId, long scheduleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todos
                    (scope_type, scope_id, title, status, priority, created_by, linked_schedule_id,
                     created_at, updated_at)
                VALUES ('PERSONAL', ?, '第四陣A linked_schedule TODO', 'OPEN', 'MEDIUM', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.setLong(2, ownerUserId);
            ps.setLong(3, scheduleId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** PERSONAL スコープの todo をシード（linked 無し）。 */
    private long insertPersonalTodo(Connection c, long ownerUserId, String title) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todos
                    (scope_type, scope_id, title, status, priority, created_by, created_at, updated_at)
                VALUES ('PERSONAL', ?, ?, 'OPEN', 'MEDIUM', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.setString(2, title);
            ps.setLong(3, ownerUserId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** action_memo を related_todo_id 付きでシード。 */
    private long insertActionMemoRelatedTodo(Connection c, long userId, long todoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO action_memos
                    (user_id, memo_date, content, related_todo_id, created_at, updated_at)
                VALUES (?, CURDATE(), '第四陣A related_todo メモ', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, todoId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** timeline_post をシード（scope_type/scope_id/user_id NOT NULL を充足）。 */
    private long insertTimelinePost(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timeline_posts
                    (scope_type, scope_id, user_id, posted_as_type, content, status, created_at, updated_at)
                VALUES ('USER', ?, ?, 'USER', '第四陣A timeline 投稿', 'PUBLISHED', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** action_memo を timeline_post_id 付きでシード。 */
    private long insertActionMemoTimelinePost(Connection c, long userId, long postId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO action_memos
                    (user_id, memo_date, content, timeline_post_id, created_at, updated_at)
                VALUES (?, CURDATE(), '第四陣A timeline_post メモ', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, postId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── generic helpers ────────────────────────────────────────

    private void deleteRow(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static long longColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? -1L : v;
            }
        }
    }

    private static boolean foreignKeyExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
