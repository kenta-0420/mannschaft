package com.mannschaft.app.billing.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・PR-1）: V203 の schema acceptance test。
 *
 * <p>V202 までを実際の Flyway/MySQL に適用して V151/V196 由来の契約行を投入した後、
 * V203（本 PR）を適用する。設計書
 * {@code docs/architecture/billing_payer_handover_design.md} §4.1/§4.2 が定める
 * DDL・CHECK 制約・生成列 UNIQUE を、H2 では検証できない MySQL 実物で固定する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.billing.migration.BillingPayerHandoverFoundationFlywayIT#isDockerAvailable")
@DisplayName("柱③-B 請求担当引継: V203 foundation Flyway acceptance")
class BillingPayerHandoverFoundationFlywayIT {

    private static final String PRE_V203_TARGET = "202.20260905015742";
    private static final String LEGACY_CONTRACT_HEX = "0199BBCCDDEEFF00112233445566AA00";
    private static final String LEGACY_CREATED_BY = "910777";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_payer_handover_foundation")
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

    @BeforeEach
    void migrateThroughV202AndSeedLegacyContract() throws Exception {
        Flyway preV203 = configuredFlyway(PRE_V203_TARGET, true);
        preV203.clean();

        MigrateResult result = preV203.migrate();
        assertThat(result.success).as("V202 までの既存 migration が成功すること").isTrue();

        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'TEAM', 920001, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-09-01 00:00:00.000000', %s)
                    """.formatted(LEGACY_CONTRACT_HEX, LEGACY_CREATED_BY));
        }
    }

    @Test
    @DisplayName("V203: payer_user_id が created_by でバックフィルされ、既存契約は不変（AC-1）")
    void payerUserIdBackfilledFromCreatedBy() throws Exception {
        migrateToV203();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT status, created_by, payer_user_id, handover_request_id
                       FROM billing_contracts
                      WHERE id = UNHEX('%s')
                     """.formatted(LEGACY_CONTRACT_HEX))) {
            assertThat(result.next()).as("legacy 契約行が残ること").isTrue();
            assertThat(result.getString("status")).isEqualTo("ACTIVE");
            assertThat(result.getLong("created_by")).isEqualTo(Long.parseLong(LEGACY_CREATED_BY));
            assertThat(result.getLong("payer_user_id")).as("payer_user_id は created_by でバックフィル")
                    .isEqualTo(Long.parseLong(LEGACY_CREATED_BY));
            assertThat(result.getObject("handover_request_id")).as("既存契約は handover 対象外").isNull();
        }
    }

    @Test
    @DisplayName("V203: Codex検分1巡目P1-1: created_by が NULL の既存契約は当該TEAMの最古参ADMINでバックフィルされる")
    void payerUserIdBackfilledFromOldestAdminWhenCreatedByIsNull() throws Exception {
        String bridgeContractHex = "0199BBCCDDEEFF00112233445566EE00";
        long teamId = 950001L;
        long olderAdminUserId = 960001L;
        long newerAdminUserId = 960002L;

        try (Connection connection = connection()) {
            insertTeam(connection, teamId);
            insertUser(connection, olderAdminUserId, "older-admin@example.test");
            insertUser(connection, newerAdminUserId, "newer-admin@example.test");
            // 最古参判定は user_roles.created_at 昇順（+id昇順のtie-break）で行う（V203 migration実装）。
            insertTeamAdminRole(connection, teamId, olderAdminUserId, "2026-01-01 00:00:00.000000");
            insertTeamAdminRole(connection, teamId, newerAdminUserId, "2026-06-01 00:00:00.000000");

            // V150.20260710030428 のブリッジ契約と同型: created_by=NULL の TEAM PLAN 契約。
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'TEAM', %d, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-08-01 00:00:00.000000', NULL)
                    """.formatted(bridgeContractHex, teamId));
        }

        migrateToV203();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT created_by, payer_user_id FROM billing_contracts
                      WHERE id = UNHEX('%s')
                     """.formatted(bridgeContractHex))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("created_by")).as("created_by の意味（作成操作者の監査記録）は変えない").isNull();
            assertThat(result.getLong("payer_user_id"))
                    .as("payer_user_id は当該TEAMの最古参ADMIN（older-admin）でバックフィルされること")
                    .isEqualTo(olderAdminUserId);
        }
    }

    @Test
    @DisplayName("V203: Codex検分1巡目P1-1: created_by がNULLかつ当該TEAMにADMINが不在ならmigration自体をfailさせる")
    void backfillGuardFailsMigrationWhenNoFallbackIsResolvable() throws Exception {
        String orphanContractHex = "0199BBCCDDEEFF00112233445566EE01";
        long teamWithoutAdminId = 950002L;

        try (Connection connection = connection()) {
            insertTeam(connection, teamWithoutAdminId);
            // ADMIN の user_roles を一切投入しない = (a)(b) いずれのフォールバックでも解決できない行。
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'TEAM', %d, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-08-01 00:00:00.000000', NULL)
                    """.formatted(orphanContractHex, teamWithoutAdminId));
        }

        assertThatThrownBy(this::migrateToV203)
                .as("payer_user_id を解決できない行が残る場合、静かにNULLを残さずmigrationをfailさせること")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("payer_user_id backfill left unresolved rows");
    }

    @Test
    @DisplayName("V203: Codex検分2巡目P1-1: USERスコープのcreated_by NULL行はmigrationをfailさせない")
    void userScopeCreatedByNullDoesNotFailMigration() throws Exception {
        String userScopeContractHex = "0199BBCCDDEEFF00112233445566EE02";

        try (Connection connection = connection()) {
            // USER スコープは payer_user_id が設計上 NULL 許容（契約者本人が自明の payer のため）。
            // created_by が NULL でも TEAM/ORG のような ADMIN 解決対象ではなく、backfill guard の
            // 対象外（scope_kind IN ('TEAM','ORG') に限定）であるべき。
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'USER', 970001, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-08-01 00:00:00.000000', NULL)
                    """.formatted(userScopeContractHex));
        }

        migrateToV203();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT created_by, payer_user_id FROM billing_contracts
                      WHERE id = UNHEX('%s')
                     """.formatted(userScopeContractHex))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("created_by")).isNull();
            assertThat(result.getObject("payer_user_id"))
                    .as("USERスコープはpayer_user_idがNULLのまま残ってよい（設計上NULL許容）")
                    .isNull();
        }
    }

    @Test
    @DisplayName("V203: Codex検分2巡目P1-2: 最古参ADMINが論理削除済みなら次順位の有効なADMINが選ばれる")
    void payerUserIdSkipsSoftDeletedOldestAdmin() throws Exception {
        String bridgeContractHex = "0199BBCCDDEEFF00112233445566EE03";
        long teamId = 950003L;
        long deletedOldestAdminUserId = 960003L;
        long activeNextAdminUserId = 960004L;

        try (Connection connection = connection()) {
            insertTeam(connection, teamId);
            insertUser(connection, deletedOldestAdminUserId, "deleted-oldest-admin@example.test");
            insertUser(connection, activeNextAdminUserId, "active-next-admin@example.test");
            // 最古参（created_at が最も早い）は deletedOldestAdminUserId だが、論理削除済みのため
            // 次順位の activeNextAdminUserId が選ばれるべき（Codex検分2巡目P1-2対応）。
            softDeleteUser(connection, deletedOldestAdminUserId);
            insertTeamAdminRole(connection, teamId, deletedOldestAdminUserId, "2026-01-01 00:00:00.000000");
            insertTeamAdminRole(connection, teamId, activeNextAdminUserId, "2026-06-01 00:00:00.000000");

            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'TEAM', %d, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-08-01 00:00:00.000000', NULL)
                    """.formatted(bridgeContractHex, teamId));
        }

        migrateToV203();

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT payer_user_id FROM billing_contracts
                      WHERE id = UNHEX('%s')
                     """.formatted(bridgeContractHex))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("payer_user_id"))
                    .as("論理削除済みの最古参ADMINを飛ばし、次順位の有効なADMINが選ばれること")
                    .isEqualTo(activeNextAdminUserId);
        }
    }

    @Test
    @DisplayName("V203: status 列は PENDING_HANDOVER（16文字）を切り捨てずに受け入れる")
    void statusColumnAcceptsPendingHandoverWithoutTruncation() throws Exception {
        migrateToV203();

        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by, payer_user_id)
                    VALUES
                        (UNHEX('0199BBCCDDEEFF00112233445566AA01'), 'TEAM', 920002, 'PLAN', 'FULL',
                         'PENDING_HANDOVER', '2026-09-05 00:00:00.000000', %s, %s)
                    """.formatted(LEGACY_CREATED_BY, LEGACY_CREATED_BY));

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT status FROM billing_contracts
                          WHERE id = UNHEX('0199BBCCDDEEFF00112233445566AA01')
                         """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("PENDING_HANDOVER");
            }
        }
    }

    @Test
    @DisplayName("V203: chk_bc_status は未知の状態値を拒否する（6値のみ許容）")
    void statusCheckRejectsUnknownValue() throws Exception {
        migrateToV203();

        try (Connection connection = connection()) {
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('0199BBCCDDEEFF00112233445566AA02'), 'TEAM', 920003, 'PLAN', 'FULL',
                         'BOGUS_STATUS', '2026-09-05 00:00:00.000000', %s)
                    """.formatted(LEGACY_CREATED_BY)))
                    .as("chk_bc_status は 6 値以外を拒否すること")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("V203: billing_payer_handover_requests は生成列+UNIQUEで同一契約の進行中要求を1件に制限する")
    void openOldContractIdGeneratedColumnEnforcesSingleOpenRequest() throws Exception {
        migrateToV203();

        try (Connection connection = connection()) {
            insertHandoverRequest(connection, "0199BBCCDDEEFF00112233445566BB01", LEGACY_CONTRACT_HEX, "REQUESTED");

            assertThatThrownBy(() -> insertHandoverRequest(
                    connection, "0199BBCCDDEEFF00112233445566BB02", LEGACY_CONTRACT_HEX, "ACCEPTED"))
                    .as("同一 old_contract_id への2件目の非終端要求は uk_bphr_open_old_contract で拒否される")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("V203: 終端状態（COMPLETED/FAILED/EXPIRED）は open_old_contract_id が NULL 化され再要求を許可する")
    void terminalStatusAllowsReRequestAfterCompletion() throws Exception {
        migrateToV203();

        try (Connection connection = connection()) {
            insertHandoverRequest(connection, "0199BBCCDDEEFF00112233445566CC01", LEGACY_CONTRACT_HEX, "COMPLETED");
            // 終端状態の行は open_old_contract_id が NULL のため、同一契約への新規（非終端）要求は許可される。
            insertHandoverRequest(connection, "0199BBCCDDEEFF00112233445566CC02", LEGACY_CONTRACT_HEX, "REQUESTED");

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT HEX(open_old_contract_id) AS open_id, status FROM billing_payer_handover_requests
                          WHERE id = UNHEX('0199BBCCDDEEFF00112233445566CC01')
                         """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("open_id")).as("終端状態は生成列がNULLになること").isNull();
            }
        }
    }

    @Test
    @DisplayName("V203: chk_bphr_status は9値の状態機械を許容し、scope_kindはTEAM/ORGのみ許容する")
    void handoverStatusCheckAllowsNineValuesAndTeamOrgScopeOnly() throws Exception {
        migrateToV203();
        // 生成列 open_old_contract_id + UNIQUE（uk_bphr_open_old_contract）は「同一契約に対する非終端要求は
        // 同時に1件のみ」を保証する（設計書§4.2）。MANUAL_INTERVENTION と PARTIALLY_COMPLETED はいずれも
        // 非終端のため、同一 old_contract_id へ両方投入すると意図どおり UNIQUE 違反になる
        // （このテストの主目的は CHECK 制約の許容値なので、それぞれ別契約に対する要求として検証する）。
        String secondContractHex = "0199BBCCDDEEFF00112233445566DD00";
        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at, created_by)
                    VALUES
                        (UNHEX('%s'), 'TEAM', 920004, 'PLAN', 'FULL', 'ACTIVE',
                         '2026-09-01 00:00:00.000000', %s)
                    """.formatted(secondContractHex, LEGACY_CREATED_BY));

            insertHandoverRequest(connection, "0199BBCCDDEEFF00112233445566DD01", LEGACY_CONTRACT_HEX,
                    "MANUAL_INTERVENTION");
            insertHandoverRequest(connection, "0199BBCCDDEEFF00112233445566DD02", secondContractHex,
                    "PARTIALLY_COMPLETED");

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO billing_payer_handover_requests
                        (id, old_contract_id, scope_kind, scope_id, old_payer_user_id, status,
                         requested_at, expires_at)
                    VALUES
                        (UNHEX('0199BBCCDDEEFF00112233445566DD03'), UNHEX('%s'), 'USER', 1, 1, 'REQUESTED',
                         '2026-09-05 00:00:00.000000', '2026-09-19 00:00:00.000000')
                    """.formatted(LEGACY_CONTRACT_HEX)))
                    .as("USER スコープは chk_bphr_scope_kind で拒否されること")
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertTeam(Connection connection, long teamId) throws SQLException {
        // teams.visibility は V79 で ENUM('PUBLIC','GUESTS_AND_ABOVE','SUPPORTERS_AND_ABOVE','MEMBERS_AND_ABOVE')
        // へ収束済み（PRIVATE は廃止値）。既定値（DEFAULT 'GUESTS_AND_ABOVE'）に任せる。
        // teams.slug は V71 で NOT NULL + UNIQUE 化されている（既定値なし）ため明示指定が必須。
        execute(connection, """
                INSERT INTO teams (id, name, slug, created_at, updated_at)
                VALUES (%d, 'V203 backfill guard team', 'v203-team-%d', NOW(6), NOW(6))
                """.formatted(teamId, teamId));
    }

    private void insertUser(Connection connection, long userId, String email) throws SQLException {
        execute(connection, """
                INSERT INTO users
                    (id, email, last_name, first_name, display_name, created_at, updated_at)
                VALUES
                    (%d, '%s', 'Test', 'User', 'Test User', NOW(6), NOW(6))
                """.formatted(userId, email));
    }

    /** users.deleted_at を設定し論理削除済みにする（Codex検分2巡目P1-2の検証用）。 */
    private void softDeleteUser(Connection connection, long userId) throws SQLException {
        execute(connection, """
                UPDATE users SET deleted_at = NOW(6) WHERE id = %d
                """.formatted(userId));
    }

    /** {@code roles.name = 'ADMIN'} を team スコープで {@code userId} に付与する（V203 backfill fallback検証用）。 */
    private void insertTeamAdminRole(Connection connection, long teamId, long userId, String createdAt)
            throws SQLException {
        execute(connection, """
                INSERT INTO user_roles (user_id, role_id, team_id, created_at, updated_at)
                SELECT %d, r.id, %d, '%s', '%s'
                  FROM roles r WHERE r.name = 'ADMIN'
                """.formatted(userId, teamId, createdAt, createdAt));
    }

    private void insertHandoverRequest(Connection connection, String idHex, String oldContractHex, String status)
            throws SQLException {
        execute(connection, """
                INSERT INTO billing_payer_handover_requests
                    (id, old_contract_id, scope_kind, scope_id, old_payer_user_id, status,
                     requested_at, expires_at)
                VALUES
                    (UNHEX('%s'), UNHEX('%s'), 'TEAM', 920001, %s, '%s',
                     '2026-09-05 00:00:00.000000', '2026-09-19 00:00:00.000000')
                """.formatted(idHex, oldContractHex, LEGACY_CREATED_BY, status));
    }

    private void migrateToV203() {
        MigrateResult result = configuredFlyway(null, false).migrate();
        assertThat(result.success).as("V203 の migration が成功すること").isTrue();
    }

    private static Flyway configuredFlyway(String target, boolean cleanEnabled) {
        if (target == null) {
            return Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .outOfOrder(false)
                    .cleanDisabled(!cleanEnabled)
                    .load();
        }
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .cleanDisabled(!cleanEnabled)
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
