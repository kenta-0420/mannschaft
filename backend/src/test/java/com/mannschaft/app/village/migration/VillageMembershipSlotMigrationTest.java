package com.mannschaft.app.village.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260827-1808: Javaの事前検査を迂回した実MySQLの所属一意・USER100村制限。
 *
 * <p>既存FlywayMeetupCandidateTimeMigrationTestのSpring不要なTC方式を使い、
 * 村・所属・代表委任の正本3migrationだけを各caseで再生する限定DDL試練。
 * 全migrationとの共存と全5業務writerのTXは別試練であり、本試練で合格を主張しない。
 * 本番候補migrationが未追加のRED段階でも、旧DBの重複受理や安全停止欠如を直接観測する。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.village.migration.VillageMembershipSlotMigrationTest#isDockerAvailable")
@DisplayName("CMP1808 所属slot・現役一意・既存移行の実MySQL契約")
@Timeout(60)
class VillageMembershipSlotMigrationTest {

    private static final String CANDIDATE_SUFFIX = "__enforce_village_membership_slots.sql";
    private static final List<String> BASE_FILES = List.of(
            "V9.125__create_villages.sql", "V9.126__create_village_memberships.sql",
            "V9.144__create_village_representatives.sql");

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cmp1808_membership_slots")
            .withUsername("test").withPassword("test").withReuse(false)
            .withTmpFs(Map.of("/var/lib/mysql", "rw,size=512m"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withMemory(1024L * 1024 * 1024).withMemorySwap(1024L * 1024 * 1024))
            .withCommand("--innodb-buffer-pool-size=64M", "--max-connections=12",
                    "--innodb-lock-wait-timeout=5")
            .withStartupTimeout(Duration.ofSeconds(120));

    @TempDir
    Path migrationDirectory;
    private Path caseMigrationDirectory;
    private final List<UUID> villages = new ArrayList<>();

    public static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    @BeforeEach
    void recreateOwnedSchemaFromCanonicalMigrations() throws Exception {
        caseMigrationDirectory = Files.createDirectory(migrationDirectory.resolve("case-" + UUID.randomUUID()));
        try (Connection c = open(); Statement s = c.createStatement()) {
            // 専用TCの固定3表だけを、子から順に落とす。共有DBには接続しない。
            s.executeUpdate("DROP TABLE IF EXISTS village_representatives");
            s.executeUpdate("DROP TABLE IF EXISTS village_memberships");
            s.executeUpdate("DROP TABLE IF EXISTS villages");
            s.executeUpdate("DROP TABLE IF EXISTS flyway_schema_history");
            s.executeUpdate("DROP PROCEDURE IF EXISTS cmp1808_assert_membership_data");
        }
        villages.clear();
        for (String file : BASE_FILES) {
            try (InputStream input = getClass().getResourceAsStream("/db/migration/" + file)) {
                assertThat(input).as("正本DDL %s", file).isNotNull();
                Files.copy(input, caseMigrationDirectory.resolve(file),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        assertThat(flyway().migrate().success).isTrue();
    }

    @AfterEach
    void assertBoundedFixtureSize() throws Exception {
        try (Connection c = open()) {
            assertThat(count(c, "SELECT COUNT(*) FROM villages")).isLessThanOrEqualTo(102);
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships")).isLessThanOrEqualTo(105);
            assertThat(count(c, "SELECT COUNT(*) FROM village_representatives")).isLessThanOrEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "TEAM", "ORGANIZATION"})
    void 同村同主体の現役NULL重複はBANを含めDBで拒否する(String type) throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            UUID village = village(c);
            membership(c, village, type, 71, 1, false, true);
            assertThatThrownBy(() -> membership(c, village, type, 71, 2, false, false))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_vm_active_subject");
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships")).isEqualTo(1);
        }
    }

    @Test
    void USERの101村目はJava検査なしでもDBで拒否する() throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            for (int slot = 1; slot <= 100; slot++) {
                membership(c, village(c), "USER", 71, slot, false, false);
            }
            UUID overflowVillage = village(c);
            assertThatThrownBy(() -> membership(c, overflowVillage, "USER", 71, 1, false, false))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_vm_user_active_slot");
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships WHERE left_at IS NULL"))
                    .isEqualTo(100);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void activeUSERの範囲外slotをCHECKが拒否する(int slot) throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            UUID village = village(c);
            assertThatThrownBy(() -> membership(c, village, "USER", 71, slot, false, false))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_vm_active_user_slot");
        }
    }

    @Test
    void activeUSERのNULLslotはUNKNOWNでCHECKを通過しない() throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            UUID village = village(c);
            assertThatThrownBy(() -> membership(c, village, "USER", 71, null, false, false))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_vm_active_user_slot");
        }
    }

    @Test
    void 退村後slot再使用と再参加は履歴を保ち履歴現役化の衝突を拒否する() throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            UUID village = village(c);
            UUID old = membership(c, village, "USER", 71, 1, false, false);
            update(c, "UPDATE village_memberships SET left_at='2026-10-04 01:00:00' WHERE id=UNHEX(?)", old);
            membership(c, village, "USER", 71, 1, false, false);
            assertThatThrownBy(() -> update(c, "UPDATE village_memberships SET left_at=NULL WHERE id=UNHEX(?)", old))
                    .isInstanceOf(SQLException.class);
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships WHERE left_at IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void 直接UPDATEも主体内slot重複を拒否し別USER同slotは許可する() throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            membership(c, village(c), "USER", 71, 1, false, false);
            UUID second = membership(c, village(c), "USER", 71, 2, false, false);
            membership(c, village(c), "USER", 72, 1, false, false);
            assertThat(hasSlot(c)).as("user_slot追加済み").isTrue();
            assertThatThrownBy(() -> update(c, "UPDATE village_memberships SET user_slot=1 WHERE id=UNHEX(?)", second))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_vm_user_active_slot");
        }
    }

    @Test
    void 村CASCADEとrollbackで枠を返し既存代表委任FKを保つ() throws Exception {
        migrateCandidateIfPresent();
        try (Connection c = open()) {
            UUID village = village(c);
            UUID member = membership(c, village, "TEAM", 71, null, false, false);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO village_representatives "
                    + "(id,village_id,membership_id,representative_user_id,granted_by_user_id) "
                    + "VALUES (UNHEX(?),UNHEX(?),UNHEX(?),71,71)")) {
                p.setString(1, hex(UUID.randomUUID())); p.setString(2, hex(village)); p.setString(3, hex(member));
                p.executeUpdate();
            }
            membership(c, village, "USER", 71, 1, false, false);
            update(c, "DELETE FROM villages WHERE id=UNHEX(?)", village);
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM village_representatives")).isZero();
            UUID next = village(c);
            c.setAutoCommit(false);
            membership(c, next, "USER", 71, 1, false, false);
            c.rollback(); c.setAutoCommit(true);
            membership(c, next, "USER", 71, 1, false, false);
            assertThat(hasSlot(c)).as("rollbackで未commitのslot予約が残らない").isTrue();
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 100})
    void 正常既存USERはid順backfillされBAN履歴非USERを保つ(int active) throws Exception {
        try (Connection c = open()) {
            for (int index = 0; index < active; index++) {
                membership(c, village(c), "USER", 71, null, false, index == 0);
            }
            UUID village = village(c);
            membership(c, village, "USER", 71, null, true, true);
            membership(c, village, "TEAM", 71, null, false, false);
            List<String> before = snapshot(c);
            migrateCandidateIfPresent();
            assertThat(snapshot(c)).as("slot以外の全membership列が同値").isEqualTo(before);
            assertThat(hasSlot(c)).as("移行が実際に追加された").isTrue();
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships WHERE subject_type='USER' "
                    + "AND left_at IS NULL AND (user_slot IS NULL OR user_slot<1 OR user_slot>100)" )).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM (SELECT user_slot,ROW_NUMBER() OVER "
                    + "(PARTITION BY subject_id ORDER BY id) expected FROM village_memberships "
                    + "WHERE subject_type='USER' AND left_at IS NULL) ranked WHERE user_slot<>expected")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM village_memberships WHERE "
                    + "(left_at IS NOT NULL OR subject_type<>'USER') AND user_slot IS NOT NULL")).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "overflow"})
    void 異常既存データはmembership変更前に安全停止し勝手に統合退村しない(String anomaly) throws Exception {
        try (Connection c = open()) {
            if ("duplicate".equals(anomaly)) {
                UUID village = village(c);
                membership(c, village, "USER", 71, null, false, false);
                membership(c, village, "USER", 71, null, false, true);
            } else {
                for (int index = 0; index < 101; index++) {
                    membership(c, village(c), "USER", 71, null, false, false);
                }
            }
            List<String> before = snapshot(c);
            assertThatThrownBy(this::migrateCandidateIfPresent).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("CMP1808");
            assertThat(snapshot(c)).as("異常所属のrole/BAN/時刻/参照/行数を変更しない").isEqualTo(before);
            assertThat(hasSlot(c)).as("事前検査が列追加より先").isFalse();
        }
    }

    private void migrateCandidateIfPresent() throws Exception {
        // REDでは候補DDLはowned evidenceのみで、productionにはまだ存在しない。
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            List<Path> candidates = files.filter(p -> p.getFileName().toString().endsWith(CANDIDATE_SUFFIX)).toList();
            assertThat(candidates.size()).as("候補migrationが重複しない").isLessThanOrEqualTo(1);
            for (Path file : candidates) {
                Files.copy(file, caseMigrationDirectory.resolve(file.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        assertThat(flyway().migrate().success).isTrue();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + caseMigrationDirectory.toAbsolutePath()).outOfOrder(false).load();
    }

    private Connection open() throws SQLException {
        String separator = MYSQL.getJdbcUrl().contains("?") ? "&" : "?";
        Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl() + separator
                        + "connectTimeout=5000&socketTimeout=15000",
                MYSQL.getUsername(), MYSQL.getPassword());
        c.setNetworkTimeout(Runnable::run, 15000);
        return c;
    }

    private UUID village(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement p = c.prepareStatement("INSERT INTO villages(id,slug,name) VALUES(UNHEX(?),?,?)")) {
            p.setString(1, hex(id)); p.setString(2, "cmp1808-" + id); p.setString(3, "試練村" + id);
            p.executeUpdate();
        }
        villages.add(id);
        return id;
    }

    private UUID membership(Connection c, UUID village, String type, long subject, Integer slot,
                            boolean left, boolean banned) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean hasSlot = hasSlot(c);
        try (PreparedStatement p = c.prepareStatement("INSERT INTO village_memberships "
                + "(id,village_id,subject_type,subject_id,left_at,banned_at" + (hasSlot ? ",user_slot" : "") + ") "
                + "VALUES(UNHEX(?),UNHEX(?),?,?," + (left ? "'2026-10-03 01:00:00'" : "NULL")
                + "," + (banned ? "'2026-10-02 01:00:00'" : "NULL") + (hasSlot ? ",?" : "") + ")")) {
            p.setString(1, hex(id)); p.setString(2, hex(village)); p.setString(3, type); p.setLong(4, subject);
            if (hasSlot) {
                if (slot == null) { p.setNull(5, Types.SMALLINT); } else { p.setInt(5, slot); }
            }
            p.executeUpdate();
        }
        return id;
    }

    private static void update(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) { p.setString(1, hex(id)); p.executeUpdate(); }
    }

    private static boolean hasSlot(Connection c) throws SQLException {
        return count(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='village_memberships' AND column_name='user_slot'") == 1;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String hex(UUID id) { return id.toString().replace("-", ""); }

    private static List<String> snapshot(Connection c) throws SQLException {
        // 生成列/user_slot以外の旧正本全列。MySQLの文字列化でバイナリを失わないようHEXにする。
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT HEX(id),HEX(village_id),"
                + "subject_type,subject_id,role,joined_at,left_at,banned_at,banned_reason,"
                + "HEX(invited_by_membership_id),created_at,updated_at,version FROM village_memberships ORDER BY id")) {
            while (r.next()) {
                List<String> values = new ArrayList<>();
                for (int col = 1; col <= 13; col++) { values.add(r.getString(col)); }
                rows.add(values.toString());
            }
        }
        return rows;
    }
}
