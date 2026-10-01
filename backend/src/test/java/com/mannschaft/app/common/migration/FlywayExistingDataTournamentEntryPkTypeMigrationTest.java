package com.mannschaft.app.common.migration;

import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryMemberEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberEntity;
import com.mannschaft.app.tournament.roster.TournamentEntryTemplateStaffEntity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.MySQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>CMP-260929-0654: 5 表 6 列の主キー・外部キー列を CHAR(36) から BINARY(16) へ移す migration（V232）の、
 * 既存データ経路の検証。</b>
 *
 * <p>V230（V232 の直前の大会エントリー系 migration）まで当てた実スキーマへ、6 列それぞれ CHAR(36) の行
 * （親子関係のあるもの）を入れてから V232 を当て、値・親子関係・制約・冪等性・途中失敗の回復を実測する。</p>
 *
 * <ul>
 *   <li>AC-5: {@code HEX(新列) = REPLACE(元,'-','')}、template_id の親子関係が保たれる、Entity 経由で読める、staff.id は不変</li>
 *   <li>AC-6: PK・UNIQUE・INDEX・FK の名前・列順・一意性・参照先・ON DELETE CASCADE が移行前と一致する</li>
 *   <li>AC-10b: 各段階（FK 削除済み・一時列追加済み・変換済み・PK 付け替え済み）を再現した状態から再実行しても最後まで通る</li>
 * </ul>
 *
 * <p>Flyway 経由の適用（@Order(1)）は実運用と同じ経路。途中状態の再現は、V232 をセミコロンで分割して
 * 先頭から N 文だけ流し、続けて全文を流す方式で行う（Flyway は適用済みの migration を二度流さないため）。
 * 途中状態を作る前に、対象 5 表を SHOW CREATE TABLE で控えた移行前の DDL へ戻す。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataTournamentEntryPkTypeMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ: 大会エントリー系・興味タグの主キーを BINARY(16) へ移行（V232）")
class FlywayExistingDataTournamentEntryPkTypeMigrationTest {

    /** V232 の直前まで（V230 系と V231 を含む）。V232 はここに含めない。 */
    private static final String PRE_V232_TARGET = "231.20260929230821";

    /** 子 → 親の順（DROP はこの順、CREATE は逆順）。 */
    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "tournament_entry_template_staff",
            "tournament_entry_template_members",
            "tournament_entry_templates",
            "tournament_entry_members",
            "user_interest_tags");

    // シード（移行前は CHAR(36)。大文字小文字を混ぜる。Hibernate は小文字で書くが、手入力の行は大文字もありうる）
    private static final String TEM_1 = "aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaa1";
    private static final String TEM_2 = "BBBBBBBB-BBBB-7BBB-8BBB-BBBBBBBBBBB2";
    private static final String TET_1 = "11111111-1111-7111-8111-111111111111";
    private static final String TET_2 = "22222222-2222-7222-8222-222222222222";
    private static final String TET_3 = "33333333-3333-7333-8333-333333333333";
    private static final String TETM_1 = "c0000001-0000-7000-8000-000000000001";
    private static final String TETM_2 = "c0000002-0000-7000-8000-000000000002";
    private static final String TETM_3 = "c0000003-0000-7000-8000-000000000003";
    private static final String STAFF_1 = "d0000001-0000-7000-8000-000000000001";
    private static final String STAFF_2 = "d0000002-0000-7000-8000-000000000002";
    private static final String UIT_1 = "e0000001-0000-7000-8000-000000000001";
    private static final String UIT_2 = "e0000002-0000-7000-8000-000000000002";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_entry_pk_migration")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    /** 移行前（V231 まで）の 5 表の DDL。 */
    private final Map<String, String> oldDdl = new LinkedHashMap<>();

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainerAndMigrateToPreV232() throws Exception {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(PRE_V232_TARGET))
                .load()
                .migrate();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String table : TABLES_CHILD_FIRST) {
                try (ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + table)) {
                    assertThat(rs.next()).as("%s が V231 までに存在すること", table).isTrue();
                    oldDdl.put(table, rs.getString(2));
                }
            }
        }
        // 前提: 移行前は本当に CHAR(36) である（この前提が崩れたらテスト自体が意味を失う）
        try (Connection conn = connect()) {
            assertThat(columnType(conn, "tournament_entry_members", "id")).isEqualTo("char(36)");
            assertThat(columnType(conn, "tournament_entry_templates", "id")).isEqualTo("char(36)");
            assertThat(columnType(conn, "tournament_entry_template_members", "id")).isEqualTo("char(36)");
            assertThat(columnType(conn, "tournament_entry_template_members", "template_id")).isEqualTo("char(36)");
            assertThat(columnType(conn, "tournament_entry_template_staff", "template_id")).isEqualTo("char(36)");
            assertThat(columnType(conn, "tournament_entry_template_staff", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "user_interest_tags", "id")).isEqualTo("char(36)");
        }
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ==================================================================
    // AC-5 / AC-6: Flyway 経由（実運用と同じ経路）
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("AC-5/AC-6: CHAR(36) の既存行が値と親子関係を保ったまま BINARY(16) へ移り、制約が元どおりそろい、Entity で読める")
    void Flyway経由で既存行と制約が保たれる() throws Exception {
        seedOldRows();
        List<String> constraintsBefore = constraintSnapshot();

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertMigrated(constraintsBefore);
    }

    @Test
    @Order(2)
    @DisplayName("AC-6: UNIQUE 制約の重複 INSERT は移行後も拒否される")
    void 移行後もUNIQUE制約が重複を拒否する() throws Exception {
        // Order(1) で移行済み。同じ組を再度入れる
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO tournament_entry_members (id, participant_id, user_id, sort_order) "
                            + "VALUES (UUID_TO_BIN(UUID()), 1, 11, 0)"))
                    .as("uq_tem_participant_user").isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_tem_participant_user");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) "
                            + "VALUES (UUID_TO_BIN(UUID()), UNHEX(REPLACE('" + TET_1 + "', '-', '')), 21, 0)"))
                    .as("uq_tetm_template_user").isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_tetm_template_user");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO user_interest_tags (id, user_id, tag, tag_hash) "
                            + "VALUES (UUID_TO_BIN(UUID()), 31, 'soccer', 'x')"))
                    .as("uq_user_interest_tag").isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_user_interest_tag");
        }
    }

    @Test
    @Order(3)
    @DisplayName("AC-6: 移行後の外部キーは ON DELETE CASCADE で動く（親の物理削除で member と staff が消える）")
    void 移行後の外部キーがCASCADEで動く() throws Exception {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long members = count(conn, "SELECT COUNT(*) FROM tournament_entry_template_members "
                    + "WHERE HEX(template_id) = UPPER(REPLACE('" + TET_2 + "', '-', ''))");
            long staff = count(conn, "SELECT COUNT(*) FROM tournament_entry_template_staff "
                    + "WHERE HEX(template_id) = UPPER(REPLACE('" + TET_2 + "', '-', ''))");
            assertThat(members).isEqualTo(1L);
            assertThat(staff).isEqualTo(1L);
            st.executeUpdate("DELETE FROM tournament_entry_templates "
                    + "WHERE HEX(id) = UPPER(REPLACE('" + TET_2 + "', '-', ''))");
            assertThat(count(conn, "SELECT COUNT(*) FROM tournament_entry_template_members "
                    + "WHERE HEX(template_id) = UPPER(REPLACE('" + TET_2 + "', '-', ''))")).isZero();
            assertThat(count(conn, "SELECT COUNT(*) FROM tournament_entry_template_staff "
                    + "WHERE HEX(template_id) = UPPER(REPLACE('" + TET_2 + "', '-', ''))")).isZero();
            // 存在しない親を指す子は作れない（FK が生きている）
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) "
                            + "VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 99, 0)"))
                    .as("fk_tetm_template").isInstanceOf(SQLException.class);
        }
    }

    // ==================================================================
    // AC-10b: 途中状態からの再実行・冪等性・変換不能行での停止
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("AC-10: 各段階（FK削除済み・一時列追加済み・変換済み・PK付け替え済み）を再現した状態から再実行しても最後まで通り、結果が同じになる")
    void 途中状態からの再実行が最後まで通る() throws Exception {
        List<String> statements = migrationStatements();
        Map<String, Integer> cuts = new LinkedHashMap<>();
        cuts.put("外部キー削除済み", cutAfterLast(statements, "DROP FOREIGN KEY"));
        cuts.put("最初の一時列だけ追加済み", cutAfterFirst(statements, "ADD COLUMN"));
        cuts.put("一時列の追加が全部済み", cutAfterLast(statements, "ADD COLUMN"));
        cuts.put("変換済み（旧列は残る）", cutAfterLast(statements, "'UPDATE "));
        cuts.put("members の PK 付け替え済み", cutAfterNth(statements, "DROP PRIMARY KEY", 1));
        cuts.put("templates の PK 付け替え済み（子は CHAR のまま・FK なし）", cutAfterNth(statements, "DROP PRIMARY KEY", 2));
        cuts.put("全表の付け替え済み・FK 未再作成", cutAfterLast(statements, "DROP PRIMARY KEY"));
        assertThat(cuts.values()).as("各段階の切れ目が見つかること").allMatch(c -> c > 0 && c < statements.size());

        for (Map.Entry<String, Integer> cut : cuts.entrySet()) {
            resetToOldSchema();
            seedOldRows();
            List<String> constraintsBefore = constraintSnapshot();
            try (Connection conn = connect()) {
                runStatements(conn, statements.subList(0, cut.getValue()));
            }
            try (Connection conn = connect()) {
                runStatements(conn, statements); // 全文を再実行
            }
            assertMigrated(constraintsBefore);
        }
    }

    @Test
    @Order(11)
    @DisplayName("AC-10: 移行済みのスキーマへ何度流しても成功し、値と制約が変わらない")
    void 移行済みへの再実行は何もしない() throws Exception {
        resetToOldSchema();
        seedOldRows();
        List<String> constraintsBefore = constraintSnapshot();
        List<String> statements = migrationStatements();
        for (int i = 0; i < 3; i++) {
            try (Connection conn = connect()) {
                runStatements(conn, statements);
            }
        }
        assertMigrated(constraintsBefore);
    }

    @Test
    @Order(12)
    @DisplayName("AC-10: 変換できない行があると旧列を消さずに止まり、行を直して再実行すれば完遂する")
    void 変換不能な行があれば旧列を消さずに止まる() throws Exception {
        resetToOldSchema();
        seedOldRows();
        List<String> constraintsBefore = constraintSnapshot();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE tournament_entry_members SET id = 'not-a-uuid' WHERE id = '" + TEM_1 + "'");
        }
        List<String> statements = migrationStatements();
        try (Connection conn = connect()) {
            assertThatThrownBy(() -> runStatements(conn, statements))
                    .as("UUID_TO_BIN が変換不能な値で失敗して止まる").isInstanceOf(SQLException.class);
        }
        try (Connection conn = connect()) {
            assertThat(columnType(conn, "tournament_entry_members", "id"))
                    .as("旧列（CHAR(36)）はまだ残っている").isEqualTo("char(36)");
            assertThat(count(conn, "SELECT COUNT(*) FROM tournament_entry_members WHERE id = 'not-a-uuid'"))
                    .as("元の行も失われていない").isEqualTo(1L);
        }
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE tournament_entry_members SET id = '" + TEM_1 + "' WHERE id = 'not-a-uuid'");
        }
        try (Connection conn = connect()) {
            runStatements(conn, statements);
        }
        assertMigrated(constraintsBefore);
    }

    // ==================================================================
    // 検証の共通部
    // ==================================================================

    private void assertMigrated(List<String> constraintsBefore) throws Exception {
        try (Connection conn = connect()) {
            // 型
            assertThat(columnType(conn, "tournament_entry_members", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "tournament_entry_templates", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "tournament_entry_template_members", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "tournament_entry_template_members", "template_id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "tournament_entry_template_staff", "template_id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "tournament_entry_template_staff", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "user_interest_tags", "id")).isEqualTo("binary(16)");
            // 一時列は残っていない
            for (String table : TABLES_CHILD_FIRST) {
                assertThat(count(conn, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME IN ('id_bin', 'template_id_bin')"))
                        .as("%s に一時列が残っていない", table).isZero();
            }

            // AC-5: HEX(新列) = REPLACE(元の文字列,'-','')（HEX は大文字のため大文字化して比較）
            assertThat(hexSet(conn, "SELECT HEX(id) FROM tournament_entry_members"))
                    .isEqualTo(normalized(TEM_1, TEM_2));
            assertThat(hexSet(conn, "SELECT HEX(id) FROM tournament_entry_templates"))
                    .isEqualTo(normalized(TET_1, TET_2, TET_3));
            assertThat(hexSet(conn, "SELECT HEX(id) FROM tournament_entry_template_members"))
                    .isEqualTo(normalized(TETM_1, TETM_2, TETM_3));
            assertThat(hexSet(conn, "SELECT HEX(id) FROM user_interest_tags"))
                    .isEqualTo(normalized(UIT_1, UIT_2));
            // 親子関係（template_id）が保たれる
            assertThat(hexSet(conn, "SELECT CONCAT(HEX(id), '>', HEX(template_id)) FROM tournament_entry_template_members"))
                    .isEqualTo(new TreeSet<>(List.of(
                            normalize(TETM_1) + ">" + normalize(TET_1),
                            normalize(TETM_2) + ">" + normalize(TET_1),
                            normalize(TETM_3) + ">" + normalize(TET_2))));
            assertThat(hexSet(conn, "SELECT CONCAT(HEX(id), '>', HEX(template_id)) FROM tournament_entry_template_staff"))
                    .isEqualTo(new TreeSet<>(List.of(
                            normalize(STAFF_1) + ">" + normalize(TET_1),
                            normalize(STAFF_2) + ">" + normalize(TET_2))));
            // 業務列が変わっていない
            assertThat(count(conn, "SELECT COUNT(*) FROM tournament_entry_members WHERE user_id IN (11, 12) "
                    + "AND participant_id = 1 AND position = 'FW' AND notes = 'メモ'")).isEqualTo(2L);
            assertThat(count(conn, "SELECT COUNT(*) FROM tournament_entry_template_members WHERE user_id IN (21, 22, 23)"))
                    .isEqualTo(3L);
            assertThat(count(conn, "SELECT COUNT(*) FROM user_interest_tags WHERE tag = 'soccer' AND tag_hash = 'hash'"))
                    .isEqualTo(2L);
        }

        // AC-6: PK・UNIQUE・INDEX・FK の名前・列順・一意性・参照先・削除規則が移行前と一致する
        List<String> constraintsAfter = constraintSnapshot();
        assertThat(constraintsAfter).as("制約（名前・列順・一意性・FK）が移行前と一致する").isEqualTo(constraintsBefore);
        assertThat(constraintsAfter).as("FK が ON DELETE CASCADE で再作成されている")
                .anyMatch(s -> s.contains("fk_tetm_template") && s.contains("CASCADE"))
                .anyMatch(s -> s.contains("fk_template_staff_template") && s.contains("CASCADE"));

        // Entity 経由で読める（Hibernate の標準 BINARY 表現と UUID_TO_BIN の並びが一致している証拠）
        try (SessionFactory sf = sessionFactory(); Session s = sf.openSession()) {
            TournamentEntryMemberEntity m1 = s.find(TournamentEntryMemberEntity.class, UUID.fromString(TEM_1));
            assertThat(m1).as("tournament_entry_members を Entity で読める").isNotNull();
            assertThat(m1.getUserId()).isEqualTo(11L);
            assertThat(m1.getPosition()).isEqualTo("FW");
            assertThat(s.find(TournamentEntryMemberEntity.class, UUID.fromString(TEM_2))).isNotNull();

            TournamentEntryTemplateEntity t1 = s.find(TournamentEntryTemplateEntity.class, UUID.fromString(TET_1));
            assertThat(t1).as("tournament_entry_templates を Entity で読める").isNotNull();
            assertThat(t1.getName()).isEqualTo("テンプレ1");

            TournamentEntryTemplateMemberEntity tm1 =
                    s.find(TournamentEntryTemplateMemberEntity.class, UUID.fromString(TETM_1));
            assertThat(tm1).as("tournament_entry_template_members を Entity で読める").isNotNull();
            assertThat(tm1.getTemplateId()).as("template_id の親子関係").isEqualTo(UUID.fromString(TET_1));
            TournamentEntryTemplateMemberEntity tm3 =
                    s.find(TournamentEntryTemplateMemberEntity.class, UUID.fromString(TETM_3));
            assertThat(tm3.getTemplateId()).isEqualTo(UUID.fromString(TET_2));

            TournamentEntryTemplateStaffEntity staff =
                    s.find(TournamentEntryTemplateStaffEntity.class, UUID.fromString(STAFF_1));
            assertThat(staff).as("tournament_entry_template_staff を Entity で読める").isNotNull();
            assertThat(staff.getId()).as("staff.id は変わらない").isEqualTo(UUID.fromString(STAFF_1));
            assertThat(staff.getTemplateId()).isEqualTo(UUID.fromString(TET_1));

            UserInterestTagEntity tag = s.find(UserInterestTagEntity.class, UUID.fromString(UIT_1));
            assertThat(tag).as("user_interest_tags を Entity で読める").isNotNull();
            assertThat(tag.getUserId()).isEqualTo(31L);
        }
    }

    // ==================================================================
    // シード・リセット・補助
    // ==================================================================

    /** 移行前（CHAR(36)）の形で行を入れる。FK は親を先に入れて満たす（participant への FK だけは検査を切る）。 */
    private void seedOldRows() throws SQLException {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.executeUpdate("INSERT INTO tournament_entry_members (id, participant_id, user_id, position, notes, sort_order) "
                    + "VALUES ('" + TEM_1 + "', 1, 11, 'FW', 'メモ', 0), ('" + TEM_2 + "', 1, 12, 'FW', 'メモ', 1)");
            st.executeUpdate("INSERT INTO tournament_entry_templates (id, team_id, name, created_by, sort_order) VALUES "
                    + "('" + TET_1 + "', 1, 'テンプレ1', 1, 0), ('" + TET_2 + "', 1, 'テンプレ2', 1, 1), "
                    + "('" + TET_3 + "', 1, 'テンプレ3', 1, 2)");
            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) VALUES "
                    + "('" + TETM_1 + "', '" + TET_1 + "', 21, 0), ('" + TETM_2 + "', '" + TET_1 + "', 22, 1), "
                    + "('" + TETM_3 + "', '" + TET_2 + "', 23, 0)");
            st.executeUpdate("INSERT INTO tournament_entry_template_staff (id, template_id, role, name, sort_order) VALUES "
                    + "(UNHEX(REPLACE('" + STAFF_1 + "', '-', '')), '" + TET_1 + "', '監督', '山田', 0), "
                    + "(UNHEX(REPLACE('" + STAFF_2 + "', '-', '')), '" + TET_2 + "', 'コーチ', '佐藤', 0)");
            st.executeUpdate("INSERT INTO user_interest_tags (id, user_id, tag, tag_hash) VALUES "
                    + "('" + UIT_1 + "', 31, 'soccer', 'hash'), ('" + UIT_2 + "', 32, 'soccer', 'hash')");
        }
    }

    /** 5 表を落として、控えておいた移行前の DDL から作り直す。 */
    private void resetToOldSchema() throws SQLException {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String table : TABLES_CHILD_FIRST) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            List<String> parentFirst = new ArrayList<>(TABLES_CHILD_FIRST);
            java.util.Collections.reverse(parentFirst);
            for (String table : parentFirst) {
                st.execute(oldDdl.get(table));
            }
        }
    }

    /** V232 をセミコロンで分割する（コメント行は除く）。 */
    private static List<String> migrationStatements() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V232.*__migrate_tournament_entry_and_interest_tag_ids_to_binary16.sql");
        assertThat(found).as("V232 の migration ファイルが classpath に在ること").hasSize(1);
        String text = new String(found[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String s : body.toString().split(";")) {
            if (!s.isBlank()) {
                statements.add(s.strip());
            }
        }
        return statements;
    }

    private static void runStatements(Connection conn, List<String> statements) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    /** 「contains を含む最後の文」を含む SET/PREPARE/EXECUTE/DEALLOCATE の 1 ブロックの終わりまでの文数。 */
    private static int cutAfterLast(List<String> statements, String contains) {
        int idx = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(contains)) {
                idx = i;
            }
        }
        return endOfBlock(statements, idx);
    }

    private static int cutAfterFirst(List<String> statements, String contains) {
        return cutAfterNth(statements, contains, 1);
    }

    private static int cutAfterNth(List<String> statements, String contains, int nth) {
        int seen = 0;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(contains) && ++seen == nth) {
                return endOfBlock(statements, i);
            }
        }
        return -1;
    }

    /** SET の次の PREPARE / EXECUTE / DEALLOCATE までを含めた文数。 */
    private static int endOfBlock(List<String> statements, int setIndex) {
        if (setIndex < 0) {
            return -1;
        }
        int i = setIndex;
        while (i < statements.size() && !statements.get(i).startsWith("DEALLOCATE")) {
            i++;
        }
        return i + 1;
    }

    /** PK・UNIQUE・INDEX（名前・列順・一意性）と FK（列・参照先・規則）を情報スキーマから文字列化する。 */
    private List<String> constraintSnapshot() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String table : TABLES_CHILD_FIRST) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
                    while (rs.next()) {
                        rows.add(table + " INDEX " + rs.getString(1) + " #" + rs.getInt(2) + " "
                                + rs.getString(3) + " non_unique=" + rs.getInt(4));
                    }
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT k.CONSTRAINT_NAME, k.ORDINAL_POSITION, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, "
                                + "k.REFERENCED_COLUMN_NAME, r.DELETE_RULE, r.UPDATE_RULE "
                                + "FROM information_schema.KEY_COLUMN_USAGE k "
                                + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                                + "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                                + "AND r.TABLE_NAME = k.TABLE_NAME "
                                + "WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = '" + table + "' "
                                + "AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION")) {
                    while (rs.next()) {
                        rows.add(table + " FK " + rs.getString(1) + " #" + rs.getInt(2) + " " + rs.getString(3)
                                + " -> " + rs.getString(4) + "." + rs.getString(5)
                                + " delete=" + rs.getString(6) + " update=" + rs.getString(7));
                    }
                }
            }
        }
        return rows;
    }

    private static String columnType(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                     + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            return rs.next() ? rs.getString(1).toLowerCase() : null;
        }
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static TreeSet<String> hexSet(Connection conn, String sql) throws SQLException {
        TreeSet<String> set = new TreeSet<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                set.add(rs.getString(1));
            }
        }
        return set;
    }

    private static String normalize(String uuidText) {
        return uuidText.replace("-", "").toUpperCase();
    }

    private static TreeSet<String> normalized(String... uuids) {
        TreeSet<String> set = new TreeSet<>();
        for (String u : uuids) {
            set.add(normalize(u));
        }
        return set;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 5 Entity だけを載せた SessionFactory（外部キー検査は切る。命名戦略は Spring Boot 既定と同一）。 */
    private static SessionFactory sessionFactory() {
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "sessionVariables=FOREIGN_KEY_CHECKS=0";
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, url)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, MYSQL.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, MYSQL.getPassword())
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        try {
            return new MetadataSources(registry)
                    .addAnnotatedClass(TournamentEntryMemberEntity.class)
                    .addAnnotatedClass(TournamentEntryTemplateEntity.class)
                    .addAnnotatedClass(TournamentEntryTemplateMemberEntity.class)
                    .addAnnotatedClass(TournamentEntryTemplateStaffEntity.class)
                    .addAnnotatedClass(UserInterestTagEntity.class)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build()
                    .buildSessionFactory();
        } catch (RuntimeException e) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw e;
        }
    }
}
