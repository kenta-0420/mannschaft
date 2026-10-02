package com.mannschaft.app.village.migration;

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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>F17.1 P1（DB Expand）既存データ番人テスト</b>:
 * 設計書 {@code docs/features/F17.1_village_headman_console_and_recruit_categories.md}
 * §5.4 / §5.5 / §5.6 の移行を、<b>実 MySQL</b>（Testcontainers）で検証する。
 * AC-20 / AC-21 / AC-21b / AC-22 / AC-22b / AC-22c / AC-23 / AC-27 / AC-28（DDL 面）に対応。
 *
 * <h2>なぜモック不可なのか</h2>
 * <p>本テストの検証対象は <b>移行 SQL そのもの</b>（seed の導出・バックフィルの結合・番人の SIGNAL・
 * FK CASCADE・NOT NULL 緩和）であり、いずれも実 RDBMS のセマンティクスでしか再現しない。
 * 設計書 §9.2 が「実 MySQL Testcontainers IT — モック不可」と明記している。
 * memory {@code feedback_adapter_mock_ut_false_green_downstream_enum} のとおり、
 * モック UT は移行 SQL の欠陥を偽 green で通す。</p>
 *
 * <h2>本テストが守る「時限爆弾」（設計書 §5.4「論理削除の罠」）</h2>
 * <p>seed / backfill / 番人を {@code deleted_at IS NULL} で絞ると、<b>論理削除済みの募集・村</b>の
 * {@code category_id} が NULL のまま残り、Stage 3（P6）の {@code NOT NULL} 化が確実に失敗する。
 * DB は {@code deleted_at} を知らないため、{@code NOT NULL} 制約は論理削除済みの行にも適用されるためである。
 * AC-22 / AC-22b / AC-22c がこの罠を機械的に検出する。</p>
 *
 * <p>方針は {@code FlywayExistingDataActivityStatusMigrationTest} を踏襲し、Spring コンテキストを
 * 起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.village.migration."
        + "FlywayExistingDataVillageRecruitCategoriesMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ 村ごと募集カテゴリマスタ移行（V153 Expand）番人テスト")
class FlywayExistingDataVillageRecruitCategoriesMigrationTest {

    /**
     * V153（本移行）の直前バージョン。ここまで適用してから
     * {@code category_id} 列がまだ無い状態の既存 village_match_recruits 行をシードする。
     */
    private static final String PRE_V153_TARGET = "152.20260711163143";

    /** 生きた村・募集実績あり（PRACTICE_MATCH ×2 / REFEREE ×1）。AC-20 の主対象。 */
    private static final UUID VILLAGE_WITH_RECRUITS = UUID.randomUUID();
    /** 生きた村・募集実績なし。AC-21 の主対象（汎用プリセット3件）。 */
    private static final UUID VILLAGE_NO_RECRUITS = UUID.randomUUID();
    /** 論理削除済みの村・募集実績なし。AC-21b の主対象（seed されない）。 */
    private static final UUID VILLAGE_DELETED_NO_RECRUITS = UUID.randomUUID();
    /** 生きた村・論理削除済みの募集のみ（VENUE）。AC-22b の主対象。 */
    private static final UUID VILLAGE_ONLY_DELETED_RECRUITS = UUID.randomUUID();
    /** 論理削除済みの村・募集あり（OTHER）。AC-22c の主対象。 */
    private static final UUID VILLAGE_DELETED_WITH_RECRUITS = UUID.randomUUID();

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_village_recruit_cat")
            .withUsername("test")
            .withPassword("test")
            // memory project_testcontainers_mysql_tmpfs_fix: tmpfs 無しは JDBC タイムアウトで落ちる
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
    void startContainerAndMigrate() throws Exception {
        MYSQL.start();

        // given: V153 の直前まで適用 ＝ village_recruit_categories が存在せず
        //        village_match_recruits.category_id 列もまだ無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V153_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V153 直前までの適用が成功すること").isTrue();

        try (Connection conn = openConn()) {
            // sanity: 旧スキーマの証明
            assertThat(tableExists(conn, "village_recruit_categories"))
                    .as("V153 適用前は village_recruit_categories が存在しないこと").isFalse();
            assertThat(columnExists(conn, "village_match_recruits", "category_id"))
                    .as("V153 適用前は category_id 列が存在しないこと").isFalse();
            assertThat(isNullable(conn, "village_match_recruits", "match_date"))
                    .as("V153 適用前は match_date が NOT NULL であること").isFalse();

            // 村をシード（生存 / 論理削除 の両系統）
            insertVillage(conn, VILLAGE_WITH_RECRUITS, "vrc-with", "VRC 募集あり村", false);
            insertVillage(conn, VILLAGE_NO_RECRUITS, "vrc-none", "VRC 募集なし村", false);
            insertVillage(conn, VILLAGE_DELETED_NO_RECRUITS, "vrc-del-none", "VRC 削除済み募集なし村", true);
            insertVillage(conn, VILLAGE_ONLY_DELETED_RECRUITS, "vrc-only-del", "VRC 削除済み募集のみ村", false);
            insertVillage(conn, VILLAGE_DELETED_WITH_RECRUITS, "vrc-del-with", "VRC 削除済み募集あり村", true);

            // 旧スキーマ（category_id 列なし）の募集行をシード
            // AC-20: 使われている値は PRACTICE_MATCH / REFEREE のみ（VENUE / OTHER は使っていない）
            insertRecruit(conn, VILLAGE_WITH_RECRUITS, "PRACTICE_MATCH", "練習相手募集1", false);
            insertRecruit(conn, VILLAGE_WITH_RECRUITS, "PRACTICE_MATCH", "練習相手募集2", false);
            insertRecruit(conn, VILLAGE_WITH_RECRUITS, "REFEREE", "審判募集1", false);
            // AC-22: 論理削除済みの募集も backfill 対象であること
            insertRecruit(conn, VILLAGE_WITH_RECRUITS, "REFEREE", "審判募集2(論理削除)", true);
            // AC-22b: 論理削除済みの募集しか持たない村
            insertRecruit(conn, VILLAGE_ONLY_DELETED_RECRUITS, "VENUE", "会場募集(論理削除)", true);
            // AC-22c: 論理削除済みの村が持つ募集
            insertRecruit(conn, VILLAGE_DELETED_WITH_RECRUITS, "OTHER", "その他募集", false);
        }

        // when: 残りのマイグレーション（V153 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success)
                .as("V153（Expand）を含む残りのマイグレーションが成功すること").isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ==================================================================
    // AC-20 / AC-21 / AC-21b — プリセット seed
    // ==================================================================

    @Test
    @DisplayName("AC-20 募集実績のある村には「実際に使われているcategory値の分だけ」seedされる（未使用値はseedされない）")
    void 募集実績のある村は使用中のcategoryのみseedされる() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(presetKeysOf(conn, VILLAGE_WITH_RECRUITS))
                    .as("使われている PRACTICE_MATCH / REFEREE のみ。VENUE / OTHER や汎用プリセットは生えない")
                    .containsExactlyInAnyOrder("PRACTICE_MATCH", "REFEREE");

            // 名称は現行 ja ラベル（village.json:421-426）
            assertThat(nameOf(conn, VILLAGE_WITH_RECRUITS, "PRACTICE_MATCH")).isEqualTo("練習試合");
            assertThat(nameOf(conn, VILLAGE_WITH_RECRUITS, "REFEREE")).isEqualTo("審判");

            // is_preset は「由来の記録」。TRUE で seed されるが不変フラグではない（設計書 §4.2）
            assertThat(isPresetOf(conn, VILLAGE_WITH_RECRUITS, "PRACTICE_MATCH")).isTrue();
        }
    }

    @Test
    @DisplayName("AC-21 募集実績の無い生きた村には汎用プリセット3件（PARTICIPANT/HELPER/OTHER）がseedされる")
    void 募集実績の無い生きた村には汎用プリセット3件がseedされる() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(presetKeysOf(conn, VILLAGE_NO_RECRUITS))
                    .as("御裁可済みの語彙・3件ちょうど（スポーツ語彙は含まれない）")
                    .containsExactlyInAnyOrder("PARTICIPANT", "HELPER", "OTHER");

            // 🔷 御裁可（§5.5）: 語彙・display_order をそのまま用いること
            assertThat(nameOf(conn, VILLAGE_NO_RECRUITS, "PARTICIPANT")).isEqualTo("参加者募集");
            assertThat(nameOf(conn, VILLAGE_NO_RECRUITS, "HELPER")).isEqualTo("協力者募集");
            assertThat(nameOf(conn, VILLAGE_NO_RECRUITS, "OTHER")).isEqualTo("その他");
            assertThat(displayOrderOf(conn, VILLAGE_NO_RECRUITS, "PARTICIPANT")).isEqualTo(10);
            assertThat(displayOrderOf(conn, VILLAGE_NO_RECRUITS, "HELPER")).isEqualTo(20);
            assertThat(displayOrderOf(conn, VILLAGE_NO_RECRUITS, "OTHER")).isEqualTo(30);
        }
    }

    @Test
    @DisplayName("AC-21b 論理削除済みで募集実績も無い村には汎用プリセットがseedされない（§5.5の非対称性）")
    void 論理削除済みで募集実績の無い村にはseedされない() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(presetKeysOf(conn, VILLAGE_DELETED_NO_RECRUITS))
                    .as("消えた村に汎用プリセットを作る意味が無い（バックフィル対象も無い）")
                    .isEmpty();
        }
    }

    // ==================================================================
    // AC-22 / AC-22b / AC-22c — バックフィル（論理削除の罠）
    // ==================================================================

    @Test
    @DisplayName("AC-22 全てのvillage_match_recruits.category_idがNULLでない（論理削除済みの行を含む）")
    void 全募集行のcategory_idが埋まっている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits WHERE category_id IS NULL"))
                    .as("【時限爆弾の番人】1件でも NULL が残ると P6 の NOT NULL 化が確実に失敗する。"
                            + "deleted_at で絞らず全行を検査すること")
                    .isZero();

            // 取りこぼしが無いことの裏返し: シードした6行すべてが健在
            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_match_recruits"))
                    .as("シードした募集行が消えていないこと（既存データ非破壊）").isEqualTo(6);
        }
    }

    @Test
    @DisplayName("AC-22b 論理削除済みの募集しか持たない村でも、その値のプリセットがseedされcategory_idがバックフィルされる")
    void 論理削除済みの募集しか無い村もseedとbackfillが効く() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(presetKeysOf(conn, VILLAGE_ONLY_DELETED_RECRUITS))
                    .as("論理削除済みの募集が使っていた VENUE も seed される（汎用プリセットではない）")
                    .containsExactly("VENUE");
            assertThat(nameOf(conn, VILLAGE_ONLY_DELETED_RECRUITS, "VENUE")).isEqualTo("会場");

            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits r "
                            + "WHERE r.village_id = " + bin(VILLAGE_ONLY_DELETED_RECRUITS)
                            + " AND r.category_id IS NULL"))
                    .as("論理削除済みの募集も category_id が埋まること").isZero();
        }
    }

    @Test
    @DisplayName("AC-22c 論理削除済みの村が持つ募集行もcategory_idがバックフィルされる")
    void 論理削除済みの村の募集もbackfillされる() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(presetKeysOf(conn, VILLAGE_DELETED_WITH_RECRUITS))
                    .as("CASCADE FK は物理削除でしか発火しないため、ソフト削除された村の募集行は生き残っている")
                    .containsExactly("OTHER");

            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits r "
                            + "WHERE r.village_id = " + bin(VILLAGE_DELETED_WITH_RECRUITS)
                            + " AND r.category_id IS NULL"))
                    .as("論理削除済みの村の募集も category_id が埋まること").isZero();
        }
    }

    @Test
    @DisplayName("AC-23 バックフィル先は「同一村」かつ「旧categoryと同じpreset_key」（村を跨いだ誤紐付けが無い）")
    void バックフィルは同一村かつ同一presetKeyを指す() throws Exception {
        try (Connection conn = openConn()) {
            // 村跨ぎの誤紐付け検出: 募集の village_id とカテゴリの village_id が違う行
            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits r "
                            + "JOIN village_recruit_categories c ON c.id = r.category_id "
                            + "WHERE c.village_id <> r.village_id"))
                    .as("村を跨いだ誤紐付けが1件もないこと").isZero();

            // 値の取り違え検出: 旧 category と preset_key が食い違う行
            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits r "
                            + "JOIN village_recruit_categories c ON c.id = r.category_id "
                            + "WHERE c.preset_key <> r.category"))
                    .as("旧 category と preset_key が食い違う行が1件もないこと").isZero();
        }
    }

    // ==================================================================
    // AC-27 — CASCADE / AC-28（DDL 面）— match_date 緩和
    // ==================================================================

    @Test
    @DisplayName("AC-27 村を物理削除するとCASCADEでカテゴリ行も消える")
    void 村の物理削除でカテゴリもCASCADE削除される() throws Exception {
        try (Connection conn = openConn();
             Statement st = conn.createStatement()) {
            // 募集を持たない村（AC-21 の対象）を物理削除する。
            // 事前に汎用プリセット3件が存在することを確認してから消す。
            assertThat(presetKeysOf(conn, VILLAGE_NO_RECRUITS)).hasSize(3);

            st.executeUpdate("DELETE FROM villages WHERE id = " + bin(VILLAGE_NO_RECRUITS));

            assertThat(presetKeysOf(conn, VILLAGE_NO_RECRUITS))
                    .as("fk_vrc_village の ON DELETE CASCADE（同一ドメイン・原則2）でカテゴリ行も消えること")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("AC-28(DDL) match_dateがNULL許容に緩和されている（§5.6 スポーツ固着の解消）")
    void matchDateがNULL許容に緩和されている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(isNullable(conn, "village_match_recruits", "match_date"))
                    .as("日付を持たない募集（マネージャー募集・引っ越し手伝い等）を登録できるようにする")
                    .isTrue();

            // 緩和方向の変更なので既存行は一切壊れない（全行が値を保持している）
            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_match_recruits WHERE match_date IS NULL"))
                    .as("既存行の match_date は消えていないこと").isZero();
        }
    }

    @Test
    @DisplayName("category_id は Stage 1 では NULL 許容のまま（NOT NULL 化は P6 / Contract）")
    void categoryIdはStage1ではNULL許容() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(isNullable(conn, "village_match_recruits", "category_id"))
                    .as("Expand 段は後方互換。NOT NULL 化は P6 で行う").isTrue();
            assertThat(columnExists(conn, "village_match_recruits", "category"))
                    .as("旧 category 列は Stage 1 では残る（DROP は P6）").isTrue();
        }
    }

    // ==================================================================
    // ヘルパー
    // ==================================================================

    private static Connection openConn() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** UUID を MySQL の BINARY(16) リテラル（{@code UNHEX('...')}）へ。 */
    private static String bin(UUID id) {
        return "UNHEX('" + id.toString().replace("-", "") + "')";
    }

    private static void insertVillage(Connection conn, UUID id, String slug, String name, boolean softDeleted)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO villages (id, slug, name, type, join_policy, visibility, "
                        + "created_at, updated_at, deleted_at) "
                        + "VALUES (UNHEX(?), ?, ?, 'COMMUNITY', 'FREE', 'PUBLIC', NOW(6), NOW(6), "
                        + (softDeleted ? "NOW(6)" : "NULL") + ")")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, slug);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    /** 旧スキーマ（category_id 列なし）の village_match_recruits へ 1 行 INSERT する。 */
    private static void insertRecruit(Connection conn, UUID villageId, String category,
                                      String title, boolean softDeleted) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_match_recruits (id, village_id, posted_by_user_id, category, "
                        + "title, match_date, status, created_at, updated_at, deleted_at) "
                        + "VALUES (UNHEX(?), UNHEX(?), 1, ?, ?, '2026-08-02', 'OPEN', NOW(6), NOW(6), "
                        + (softDeleted ? "NOW(6)" : "NULL") + ")")) {
            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
            ps.setString(2, villageId.toString().replace("-", ""));
            ps.setString(3, category);
            ps.setString(4, title);
            ps.executeUpdate();
        }
    }

    /** 指定村のカテゴリ preset_key 一覧（論理削除は seed 直後に存在しない前提で絞らない）。 */
    private static List<String> presetKeysOf(Connection conn, UUID villageId) throws Exception {
        List<String> keys = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT preset_key FROM village_recruit_categories "
                             + "WHERE village_id = " + bin(villageId))) {
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
        }
        return keys;
    }

    private static String nameOf(Connection conn, UUID villageId, String presetKey) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM village_recruit_categories "
                             + "WHERE village_id = " + bin(villageId)
                             + " AND preset_key = '" + presetKey + "'")) {
            assertThat(rs.next()).as(presetKey + " のカテゴリ行が存在すること").isTrue();
            return rs.getString(1);
        }
    }

    private static int displayOrderOf(Connection conn, UUID villageId, String presetKey) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT display_order FROM village_recruit_categories "
                             + "WHERE village_id = " + bin(villageId)
                             + " AND preset_key = '" + presetKey + "'")) {
            assertThat(rs.next()).as(presetKey + " のカテゴリ行が存在すること").isTrue();
            return rs.getInt(1);
        }
    }

    private static boolean isPresetOf(Connection conn, UUID villageId, String presetKey) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT is_preset FROM village_recruit_categories "
                             + "WHERE village_id = " + bin(villageId)
                             + " AND preset_key = '" + presetKey + "'")) {
            assertThat(rs.next()).as(presetKey + " のカテゴリ行が存在すること").isTrue();
            return rs.getBoolean(1);
        }
    }

    private static long countScalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws Exception {
        return countScalar(conn,
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'") > 0;
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        return countScalar(conn,
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                        + "AND COLUMN_NAME = '" + column + "'") > 0;
    }

    private static boolean isNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }
}
