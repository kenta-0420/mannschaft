package com.mannschaft.app.reservation.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261104-0334 AC 0334-8（後半）: 既存の臨時休業行がある DB に
 * {@code V257.20261104102344__add_idempotency_key_to_emergency_closures.sql} を当てる移行試練。
 *
 * <ol>
 *   <li>V257 の直前まで適用 → 既存の休業行（同じチーム・同じ送信者で2件）を投入</li>
 *   <li>残り（V257 含む）を適用しても成功し、既存行は内容ごと保たれる</li>
 *   <li>新列 {@code idempotency_key} / {@code request_hash} は NULL 許容で、既存行は NULL</li>
 *   <li>一意制約 {@code uq_emergency_closures_team_creator_idempotency (team_id, created_by, idempotency_key)} が
 *       実在し、同じ組の2行目は 1062 で拒否、送信者違い・キー NULL は通る</li>
 * </ol>
 *
 * <p>Spring を起動せず Testcontainers の実 MySQL 8.0 に Flyway を Java API で直接実行する（既存の
 * {@code FlywayExistingData*MigrationTest} と同じ型）。Docker 未起動環境では {@code @EnabledIf} でスキップする。
 * 冪等キーは固定リテラルを置かず実行時に生成する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataEmergencyClosureIdempotencyMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ emergency_closures 冪等キー追加（V257）移行試練")
class FlywayExistingDataEmergencyClosureIdempotencyMigrationTest {

    /** 本試練の対象（冪等キー追加）の版。 */
    private static final MigrationVersion TARGET_MIGRATION = MigrationVersion.fromVersion("257.20261104102344");
    private static final String CONSTRAINT = "uq_emergency_closures_team_creator_idempotency";
    private static final long TEAM_ID = 880_001L;
    private static final long CREATOR_ID = 880_101L;
    private static final long OTHER_CREATOR_ID = 880_102L;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_ec_idempotency")
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

    @Test
    @DisplayName("0334-8: 既存休業行があるDBにV257を当てても行は保たれ、新列はNULL、一意制約が効く")
    void 既存データを持つDBでV257が安全に適用される() throws Exception {
        MigrateResult preResult = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(versionJustBeforeTarget())
                .load()
                .migrate();
        assertThat(preResult.success).as("V257 直前（実在する最大版）までの適用が成功すること").isTrue();

        List<Long> existing = new ArrayList<>();
        try (Connection c = conn()) {
            assertThat(columnExists(c, "idempotency_key")).as("V257 前は冪等列が無い").isFalse();
            // 同じチーム・同じ送信者の既存行を2件（キー NULL どうしが一意制約に当たらないことも兼ねて確かめる）。
            existing.add(insertLegacyClosure(c, "既存休業A"));
            existing.add(insertLegacyClosure(c, "既存休業B"));
        }

        MigrateResult restResult = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(restResult.success).as("V257 を含む残りの適用が成功すること").isTrue();

        try (Connection c = conn()) {
            // 既存行が内容ごと保たれ、新列は NULL
            assertThat(reasonOf(c, existing.get(0))).isEqualTo("既存休業A");
            assertThat(reasonOf(c, existing.get(1))).isEqualTo("既存休業B");
            for (Long id : existing) {
                assertThat(nullColumns(c, id)).as("既存行の idempotency_key / request_hash は NULL").isEqualTo(2);
            }
            // 列定義
            assertThat(columnType(c, "idempotency_key")).isEqualTo("char(36)");
            assertThat(columnType(c, "request_hash")).isEqualTo("char(64)");
            assertThat(isNullable(c, "idempotency_key")).isTrue();
            assertThat(isNullable(c, "request_hash")).isTrue();
            // 一意制約の実在と列順
            assertThat(uniqueIndexColumns(c)).containsExactly("team_id", "created_by", "idempotency_key");

            // 一意制約が効く: 同じ (チーム, 送信者, キー) の2行目は 1062 で拒否
            String key = UUID.randomUUID().toString();
            insertKeyedClosure(c, CREATOR_ID, key);
            assertThatThrownBy(() -> insertKeyedClosure(c, CREATOR_ID, key))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .satisfies(e -> {
                        assertThat(((SQLException) e).getErrorCode()).isEqualTo(1062);
                        assertThat(e.getMessage()).contains(CONSTRAINT);
                    });
            // 送信者が違えば同じキーでも通る（キーはチーム×送信者で区別）
            insertKeyedClosure(c, OTHER_CREATOR_ID, key);
            // キー NULL は何件でも通る（ヘッダ無しの従来送信）
            insertLegacyClosure(c, "移行後のキー無し送信");
            assertThat(countClosures(c)).isEqualTo(5);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    /**
     * classpath 上の移行を列挙し、対象（V257）より前で実在する最大の版を返す。
     *
     * <p>Flyway の target は実在する版でなければならないため、版番号を決め打ちしない。main に新しい版が
     * 入っても（対象より前・後のどちらでも）この試練が壊れないよう、毎回実ファイルから求める。</p>
     */
    private MigrationVersion versionJustBeforeTarget() {
        MigrationInfo[] all = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .info()
                .all();
        assertThat(Arrays.stream(all).map(MigrationInfo::getVersion).filter(Objects::nonNull))
                .as("対象の移行が classpath に実在する").contains(TARGET_MIGRATION);
        return Arrays.stream(all)
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .filter(version -> version.compareTo(TARGET_MIGRATION) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("対象より前の移行が見つからない"));
    }

    private long insertLegacyClosure(Connection c, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO emergency_closures
                    (team_id, start_date, end_date, reason, subject, message_body, created_by, created_at, updated_at)
                VALUES (?, CURDATE(), CURDATE(), ?, '臨時休業のお知らせ', '本文', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, TEAM_ID);
            ps.setString(2, reason);
            ps.setLong(3, CREATOR_ID);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertKeyedClosure(Connection c, long creatorId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO emergency_closures
                    (team_id, start_date, end_date, reason, subject, message_body, created_by,
                     idempotency_key, request_hash, created_at, updated_at)
                VALUES (?, CURDATE(), CURDATE(), '冪等キー付き', '臨時休業のお知らせ', '本文', ?, ?, ?, NOW(), NOW())
                """)) {
            ps.setLong(1, TEAM_ID);
            ps.setLong(2, creatorId);
            ps.setString(3, key);
            ps.setString(4, "0".repeat(64));
            ps.executeUpdate();
        }
    }

    private String reasonOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT reason FROM emergency_closures WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int nullColumns(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT (idempotency_key IS NULL) + (request_hash IS NULL) "
                + "FROM emergency_closures WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long countClosures(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM emergency_closures WHERE team_id = ?")) {
            ps.setLong(1, TEAM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private boolean columnExists(Connection c, String column) throws SQLException {
        return columnType(c, column) != null;
    }

    private String columnType(Connection c, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emergency_closures' AND COLUMN_NAME = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1).toLowerCase() : null;
            }
        }
    }

    private boolean isNullable(Connection c, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emergency_closures' AND COLUMN_NAME = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "YES".equals(rs.getString(1));
            }
        }
    }

    private List<String> uniqueIndexColumns(Connection c) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emergency_closures' AND INDEX_NAME = ? "
                + "AND NON_UNIQUE = 0 ORDER BY SEQ_IN_INDEX")) {
            ps.setString(1, CONSTRAINT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }
}
