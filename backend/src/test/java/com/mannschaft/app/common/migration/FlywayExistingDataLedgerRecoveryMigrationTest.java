package com.mannschaft.app.common.migration;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（V72.006 の旧 CHECK で許可された 6 種の entry_type を含む ledger_entries 行）を持つ
 * MySQL に対し、V84.002（{@code chk_le_entry_type} に RECOVERY 追加）＋ V84.003（{@code recovery_kind} 列と
 * {@code chk_le_recovery_kind} 追加）を含む全マイグレーションがクラッシュせず適用でき、既存行が生存し、
 * かつ新値 RECOVERY と recovery_kind 付き RECOVERY 行も INSERT でき、<b>RECOVERY 行で recovery_kind=NULL が
 * 厳格 CHECK で弾かれ（静かな金銭ドロップ防止・4 値必須）、4 値はいずれも通り、非 RECOVERY 行の recovery_kind が
 * CHECK で弾かれること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V84.002 は旧 CHECK 制約 {@code chk_le_entry_type}（6値）を DROP してから 7 値
 * （既存6値＋RECOVERY）で再作成する。{@link FlywayFromScratchMigrationTest}（空 DB 番人）では
 * ledger_entries が 0 行のため「既存行が CHECK 再作成後も生存するか」「DROP 漏れで RECOVERY が
 * 弾かれないか」を検知できない（feedback_flyway_existing_data_check_drop の盲点）。
 * 本テストは <b>V84.001 まで適用 → 旧 6 種の entry_type 行をシード（旧 CHECK が実効）→
 * 残り（V84.002 含む）を適用</b>という既存データ経路を再現し、本作法の破綻を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>{@link FlywayExistingDataTeamVisibilityMigrationTest} と同様、Spring コンテキストを起動せず
 * Testcontainers の実 MySQL 8.0 に対して {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataLedgerRecoveryMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ ledger RECOVERY 追加（V84.002）番人テスト")
class FlywayExistingDataLedgerRecoveryMigrationTest {

    /** V84.002 の直前バージョン。ここまで適用してから旧 6 種の entry_type 行をシードする。 */
    private static final String PRE_V84_002_TARGET = "84.001";

    /** 既存6種（V72.006 の旧 CHECK が許可する値）。 */
    private static final String[] EXISTING_ENTRY_TYPES =
            {"AUTHORIZE", "CAPTURE", "TRANSFER_OUT", "FEE", "REFUND", "CANCEL"};

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_ledger_recovery")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
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

    /**
     * 既存データ経路: V84.001 まで適用 → 旧 6 種の entry_type 行をシード → 残り（V84.002）を適用し、
     * 既存行が生存し RECOVERY が挿入可能・CHECK 制約が 7 値であることを検証する。
     */
    @Test
    @DisplayName("旧6種entry_typeを持つDBにV84.002適用_既存行生存かつRECOVERY挿入可能")
    void 既存データを持つDBでV84_002が安全に適用される() throws Exception {
        // given: V84.002 の直前（V84.001）まで適用 ＝ chk_le_entry_type は旧 6 値の状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V84_002_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V84.001 までの適用が成功すること").isTrue();

        UUID escrowId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: 旧 CHECK が存在する（旧スキーマの担保）
            assertThat(checkConstraintExists(c, "chk_le_entry_type"))
                    .as("V84.001 時点では旧 CHECK 制約 chk_le_entry_type が存在すること")
                    .isTrue();

            // FK fk_le_escrow（escrow_transaction_id → escrow_transactions.id）を満たす親を 1 件作成。
            insertEscrowParent(c, escrowId);

            // 旧 6 種の entry_type を持つ ledger_entries 行をシード。
            // ※ 旧 CHECK が実効しているため、ここで 6 値以外を入れようとすると失敗する＝旧スキーマの証明。
            for (String type : EXISTING_ENTRY_TYPES) {
                insertLedgerEntry(c, UUID.randomUUID(), escrowId, type);
            }
            assertThat(countLedgerEntries(c)).as("シードした旧 6 行が存在すること").isEqualTo(6);
        }

        // when: 残りのマイグレーション（V84.002 含む）を適用する。
        // 旧 CHECK の DROP 漏れがあればこの後の RECOVERY INSERT で CHECK 違反になる。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V84.002 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 既存 6 行は ALTER 後も全て生存している（V84.003 の recovery_kind 列追加・新 CHECK でも生存）
            assertThat(countLedgerEntries(c)).as("既存 6 行が ALTER 後も生存していること").isEqualTo(6);

            // then-2: 新値 RECOVERY を INSERT できる（旧 CHECK の DROP→7値 ADD が効いている）。
            // V84.003 の厳格 CHECK 下では RECOVERY 行は recovery_kind 必須（NULL 不可）のため、
            // 有効な kind（C1_ACCRUAL）を付与して INSERT が通ることを確認する。
            insertRecoveryEntry(c, UUID.randomUUID(), escrowId, "C1_ACCRUAL");
            assertThat(countLedgerEntries(c)).as("RECOVERY 行が追加され計 7 行になること").isEqualTo(7);

            // then-3: CHECK 制約自体は引き続き存在する（DROP しっぱなしになっていない）
            assertThat(checkConstraintExists(c, "chk_le_entry_type"))
                    .as("V84.002 適用後も chk_le_entry_type（7値）が存在すること")
                    .isTrue();

            // then-4（V84.003）: recovery_kind 列と chk_le_recovery_kind が存在する
            assertThat(checkConstraintExists(c, "chk_le_recovery_kind"))
                    .as("V84.003 適用後 chk_le_recovery_kind が存在すること")
                    .isTrue();

            // then-5（V84.003 厳格 CHECK）: RECOVERY 行は 4 値（C1_ACCRUAL/C2_COMPLETION/A_EXECUTION/A_RECAPITALIZE）が
            // いずれも通る。4 経路すべてが kind を設定するため、4 値全てを INSERT できることを担保する。
            String[] validKinds = {"C1_ACCRUAL", "C2_COMPLETION", "A_EXECUTION", "A_RECAPITALIZE"};
            for (String kind : validKinds) {
                insertRecoveryEntry(c, UUID.randomUUID(), escrowId, kind);
            }
            // 既存 6 ＋ then-2 の RECOVERY(C1_ACCRUAL) 1 ＋ ここで 4 値 4 行 ＝ 計 11 行。
            assertThat(countLedgerEntries(c))
                    .as("4 値の recovery_kind 付き RECOVERY 行が追加され計 11 行になること")
                    .isEqualTo(11);

            // then-6（V84.003 厳格 CHECK・静かな金銭ドロップ防止）: RECOVERY 行で recovery_kind=NULL は弾かれる。
            // NULL 許容を残すと峻別が曖昧になり回収金消失の穴が再発するため、NOT NULL 相当を CHECK で強制する。
            assertThat(insertRecoveryWithNullKindFails(c, escrowId))
                    .as("RECOVERY 行で recovery_kind=NULL は chk_le_recovery_kind 違反で弾かれること")
                    .isTrue();
            assertThat(countLedgerEntries(c)).as("弾かれた行は挿入されず計 11 行のままであること").isEqualTo(11);

            // then-7（V84.003）: 非 RECOVERY 行に recovery_kind を持たせると CHECK 違反で弾かれる（NULL 強制）
            assertThat(insertNonRecoveryWithKindFails(c, escrowId))
                    .as("非 RECOVERY 行に recovery_kind を入れると chk_le_recovery_kind 違反で弾かれること")
                    .isTrue();
            assertThat(countLedgerEntries(c)).as("弾かれた行は挿入されず計 11 行のままであること").isEqualTo(11);
        }
    }

    /** RECOVERY 行を recovery_kind=NULL で INSERT し、厳格 CHECK 違反で弾かれたら true（4 値必須の担保）。 */
    private boolean insertRecoveryWithNullKindFails(Connection c, UUID escrowId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ledger_entries
                    (id, escrow_transaction_id, entry_type, account, direction,
                     amount, running_balance, recovery_kind)
                VALUES (?, ?, 'RECOVERY', 'PAYEE', 'D', 100, 100, NULL)
                """)) {
            ps.setBytes(1, toBytes(UUID.randomUUID()));
            ps.setBytes(2, toBytes(escrowId));
            ps.executeUpdate();
            return false; // 挿入できてしまった＝CHECK が緩く NULL を許している
        } catch (SQLException expected) {
            return true; // CHECK 違反で弾かれた（期待どおり）
        }
    }

    /** RECOVERY 行を recovery_kind 付きで 1 行 INSERT する（V84.003 検証用）。 */
    private void insertRecoveryEntry(Connection c, UUID id, UUID escrowId, String recoveryKind)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ledger_entries
                    (id, escrow_transaction_id, entry_type, account, direction,
                     amount, running_balance, recovery_kind)
                VALUES (?, ?, 'RECOVERY', 'PAYEE', 'D', 100, 100, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(escrowId));
            ps.setString(3, recoveryKind);
            ps.executeUpdate();
        }
    }

    /** 非 RECOVERY 行に recovery_kind を持たせて INSERT し、chk_le_recovery_kind 違反で弾かれたら true。 */
    private boolean insertNonRecoveryWithKindFails(Connection c, UUID escrowId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ledger_entries
                    (id, escrow_transaction_id, entry_type, account, direction,
                     amount, running_balance, recovery_kind)
                VALUES (?, ?, 'FEE', 'PLATFORM_FEE', 'C', 100, 100, 'A_EXECUTION')
                """)) {
            ps.setBytes(1, toBytes(UUID.randomUUID()));
            ps.setBytes(2, toBytes(escrowId));
            ps.executeUpdate();
            return false; // 挿入できてしまった＝CHECK が効いていない
        } catch (SQLException expected) {
            return true; // CHECK 違反で弾かれた（期待どおり）
        }
    }

    /** FK を満たすため escrow_transactions を 1 行 INSERT する（NOT NULL かつ DEFAULT 無しの列を充足）。 */
    private void insertEscrowParent(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO escrow_transactions
                    (id, source_kind, source_id, payer_scope_kind, payer_scope_id,
                     payee_kind, payee_connect_account_id, amount, face_amount,
                     created_at, updated_at)
                VALUES (?, 'RECRUITMENT', 1, 'USER', 1, 'TEAM', ?, 10250, 10000, NOW(), NOW())
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(UUID.randomUUID()));
            ps.executeUpdate();
        }
    }

    /** ledger_entries を 1 行 INSERT する（NOT NULL かつ DEFAULT 無しの列を充足）。 */
    private void insertLedgerEntry(Connection c, UUID id, UUID escrowId, String entryType)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ledger_entries
                    (id, escrow_transaction_id, entry_type, account, direction,
                     amount, running_balance)
                VALUES (?, ?, ?, 'PLATFORM_FEE', 'C', 100, 100)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(escrowId));
            ps.setString(3, entryType);
            ps.executeUpdate();
        }
    }

    private static long countLedgerEntries(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger_entries")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean checkConstraintExists(Connection c, String constraintName) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND CONSTRAINT_TYPE = 'CHECK' "
                             + "AND CONSTRAINT_NAME = '" + constraintName + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (hi >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (lo >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
