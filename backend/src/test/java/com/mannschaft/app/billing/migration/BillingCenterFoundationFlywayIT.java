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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20.1 料金・契約センター PR1 の schema acceptance test。
 *
 * <p>V195 までを実際の Flyway/MySQL に適用して V151 由来の契約行を投入した後、
 * V196 相当を含む残りの migration を適用する。production Service を経由せず、
 * migration 自体が守るべき土台だけを検証する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.billing.migration.BillingCenterFoundationFlywayIT#isDockerAvailable")
@DisplayName("F20.1 料金・契約センター: V196 foundation Flyway acceptance")
class BillingCenterFoundationFlywayIT {

    private static final String PRE_V196_TARGET = "195.20260830010000";
    private static final String LEGACY_CONTRACT_HEX = "0199AABBCCDDEEFF0011223344556677";
    private static final String LEGACY_CUSTOMER_REF = "cus_v151_contract_guard";
    private static final String LEGACY_SUBSCRIPTION_REF = "sub_v151_contract_guard";

    private static final List<String> CLEANUP_ORDER = List.of(
            "stripe_webhook_events",
            "billing_invoice_lines",
            "billing_invoice_adjustments",
            "active_billing_contract_operation_pointers",
            "active_contract_pointers",
            "billing_customer_migrations",
            "billing_membership_price_adjustments",
            "billing_contract_changes",
            "billing_contract_operations",
            "billing_change_previews",
            "billing_quotes",
            "billing_return_state_nonces",
            "billing_api_idempotencies",
            "billing_invoices",
            "billing_contracts",
            "billing_price_band_versions",
            "billing_price_versions",
            "billing_customers");

    private static final Set<String> BILLING_DOMAIN_TABLES = Set.copyOf(CLEANUP_ORDER);

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_billing_foundation")
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
    void migrateThroughV195AndSeedV151Contract() throws Exception {
        Flyway preV196 = configuredFlyway(PRE_V196_TARGET, true);
        preV196.clean();

        MigrateResult result = preV196.migrate();
        assertThat(result.success).as("V195 までの既存 migration が成功すること").isTrue();

        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO billing_contracts
                        (id, scope_kind, scope_id, contract_kind, plan_key, status, contracted_at,
                         psp_customer_ref, psp_subscription_ref)
                    VALUES
                        (UNHEX('%s'), 'USER', 910001, 'PLAN', 'BASIC', 'ACTIVE',
                         '2026-08-31 00:00:00.000000', '%s', '%s')
                    """.formatted(LEGACY_CONTRACT_HEX, LEGACY_CUSTOMER_REF, LEGACY_SUBSCRIPTION_REF));
        }
    }

    @Test
    @DisplayName("V196 は legacy 契約を保持し、課金基盤・管理権限・販売ガード・FK cleanup 順を提供する")
    void v196FoundationContract() throws Exception {
        MigrateResult result = configuredFlyway(null, false).migrate();
        assertThat(result.success).as("V196 相当を含む残りの migration が成功すること").isTrue();

        try (Connection connection = connection()) {
            assertV196SchemaAndAlterations(connection);
            assertV151ContractIsUnchanged(connection);
            assertBillingPermissionsAreAdminDefaultsOnly(connection);
            assertZeroPriceBandCannotBecomeSellable(connection);
            assertBillingForeignKeysAndCleanupOrder(connection);
        }
    }

    @Test
    @DisplayName("V196 は孤児ポインタを検出し、Expand開始前に安全停止する")
    void orphanActiveContractPointerStopsBeforeExpand() throws Exception {
        try (Connection connection = connection()) {
            execute(connection, """
                    INSERT INTO active_contract_pointers
                        (id, scope_kind, scope_id, contract_kind, addon_feature_key, contract_id)
                    VALUES
                        (UNHEX('0199AABBCCDDEEFF0011223344556681'), 'USER', 910099,
                         'PLAN', '', UNHEX('0199AABBCCDDEEFF0011223344556682'))
                    """);
        }

        assertThatThrownBy(() -> configuredFlyway(null, false).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("orphan active contract pointer");

        try (Connection connection = connection()) {
            assertThat(queryStrings(connection, """
                    SELECT table_name
                      FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                    """))
                    .as("番人失敗時はExpandの最初の表も作られないこと")
                    .doesNotContain("billing_customers");
        }
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

    private static void assertV196SchemaAndAlterations(Connection connection) throws Exception {
        Set<String> expectedTables = Set.of(
                "billing_customers",
                "billing_price_versions",
                "billing_price_band_versions",
                "billing_quotes",
                "billing_change_previews",
                "billing_contract_changes",
                "billing_contract_operations",
                "active_billing_contract_operation_pointers",
                "billing_membership_price_adjustments",
                "billing_customer_migrations",
                "billing_invoices",
                "billing_invoice_adjustments",
                "billing_invoice_lines",
                "billing_return_state_nonces",
                "billing_api_idempotencies");

        Set<String> actualTables = queryStrings(connection, """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                """);
        assertThat(actualTables).as("V196 の課金基盤テーブル").containsAll(expectedTables);

        assertColumnLength(connection, "billing_contracts", "psp_customer_ref", 255);
        assertColumnLength(connection, "billing_contracts", "psp_subscription_ref", 255);
        assertColumnsExist(connection, "billing_contracts",
                "billing_customer_id", "price_band_version_id", "billing_cycle_anchor_at",
                "cancel_scheduled_at", "version");
        assertColumnsExist(connection, "stripe_webhook_events",
                "billing_contract_id", "billing_customer_id", "stripe_object_ref", "payload_sha256",
                "failed_at", "attempt_count");
    }

    private static void assertV151ContractIsUnchanged(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT status, psp_customer_ref, psp_subscription_ref
                       FROM billing_contracts
                      WHERE id = UNHEX('%s')
                     """.formatted(LEGACY_CONTRACT_HEX))) {
            assertThat(result.next()).as("V151 由来の契約行が残ること").isTrue();
            assertThat(result.getString("status")).isEqualTo("ACTIVE");
            assertThat(result.getString("psp_customer_ref")).isEqualTo(LEGACY_CUSTOMER_REF);
            assertThat(result.getString("psp_subscription_ref")).isEqualTo(LEGACY_SUBSCRIPTION_REF);
            assertThat(result.next()).as("契約行が複製されないこと").isFalse();
        }
    }

    private static void assertBillingPermissionsAreAdminDefaultsOnly(Connection connection) throws Exception {
        assertPermission(connection, "MANAGE_TEAM_BILLING", "TEAM");
        assertPermission(connection, "MANAGE_ORGANIZATION_BILLING", "ORGANIZATION");
    }

    private static void assertPermission(Connection connection, String permission, String scope) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT p.scope, r.name, rp.is_default
                       FROM permissions p
                       JOIN role_permissions rp ON rp.permission_id = p.id
                       JOIN roles r ON r.id = rp.role_id
                      WHERE p.name = '%s'
                     """.formatted(permission))) {
            int rows = 0;
            while (result.next()) {
                rows++;
                assertThat(result.getString("scope")).isEqualTo(scope);
                assertThat(result.getString("name")).as("既定付与先").isEqualTo("ADMIN");
                assertThat(result.getBoolean("is_default")).as("ADMIN の既定付与").isTrue();
            }
            assertThat(rows).as("%s は ADMIN だけに既定付与されること", permission).isEqualTo(1);
        }
    }

    private static void assertZeroPriceBandCannotBecomeSellable(Connection connection) throws Exception {
        String versionId = "0199AABBCCDDEEFF0011223344556601";
        String bandId = "0199AABBCCDDEEFF0011223344556602";
        insertPriceVersion(connection, versionId, "ZERO_GUARD", "DRAFT");
        insertPriceBand(connection, bandId, versionId, "ZERO_GUARD", 0, "DRAFT", null);

        assertThatThrownBy(() -> execute(connection, """
                UPDATE billing_price_band_versions
                   SET status = 'READY', stripe_price_ref = 'price_zero_must_not_sell'
                 WHERE id = UNHEX('%s')
                """.formatted(bandId)))
                .as("0円 band は Stripe Price を得ても販売可能状態に遷移できないこと")
                .isInstanceOf(SQLException.class);
    }

    private static void insertPriceVersion(Connection connection, String id, String productKey, String status)
            throws SQLException {
        execute(connection, """
                INSERT INTO billing_price_versions
                    (id, product_kind, product_key, scope_kind, catalog_revision, revision_no,
                     status, effective_from, lock_version, creation_source)
                VALUES
                    (UNHEX('%s'), 'PLAN', '%s', 'TEAM', '%s', 1, '%s',
                     '2026-09-01 00:00:00.000000', 0, 'SYSTEM_BACKFILL')
                """.formatted(id, productKey, productKey, status));
    }

    private static void insertPriceBand(
            Connection connection,
            String id,
            String priceVersionId,
            String productKey,
            long inputAmount,
            String status,
            String stripePriceRef) throws SQLException {
        String stripeRefSql = stripePriceRef == null ? "NULL" : "'" + stripePriceRef + "'";
        execute(connection, """
                INSERT INTO billing_price_band_versions
                    (id, product_kind, product_key, scope_kind, band_no, min_members, max_members,
                     price_version_id, stripe_price_ref, currency, input_amount, tax_behavior,
                     tax_code_snapshot, tax_master_snapshot, amount_excluding_tax, tax_amount,
                     tax_rate_basis_points, tax_name_snapshot, is_included_in_price,
                     amount_including_tax, effective_from, status, lock_version, creation_source)
                VALUES
                    (UNHEX('%s'), 'PLAN', '%s', 'TEAM', 1, 1, NULL,
                     UNHEX('%s'), %s, 'JPY', %d, 'INCLUSIVE',
                     'txcd_00000000', JSON_OBJECT(), %d, 0,
                     0, 'non-taxable', TRUE,
                     %d, '2026-09-01 00:00:00.000000', '%s', 0, 'SYSTEM_BACKFILL')
                """.formatted(id, productKey, priceVersionId, stripeRefSql, inputAmount, inputAmount, inputAmount, status));
    }

    private static void assertBillingForeignKeysAndCleanupOrder(Connection connection) throws Exception {
        List<ForeignKey> foreignKeys = queryForeignKeys(connection);
        Set<ForeignKey> actual = Set.copyOf(foreignKeys);
        assertThat(actual).contains(
                new ForeignKey("billing_price_band_versions", "billing_price_versions"),
                new ForeignKey("billing_contracts", "billing_customers"),
                new ForeignKey("billing_contracts", "billing_price_band_versions"),
                new ForeignKey("billing_invoice_lines", "billing_invoices"),
                new ForeignKey("billing_invoice_lines", "billing_price_band_versions"),
                new ForeignKey("active_billing_contract_operation_pointers", "billing_contract_operations"));

        assertThat(foreignKeys)
                .as("billing domain の FK は cross-domain 参照を持たないこと")
                .allSatisfy(foreignKey -> assertThat(BILLING_DOMAIN_TABLES)
                        .contains(foreignKey.childTable(), foreignKey.parentTable()));

        Map<String, Integer> cleanupRanks = java.util.stream.IntStream.range(0, CLEANUP_ORDER.size())
                .boxed()
                .collect(java.util.stream.Collectors.toMap(CLEANUP_ORDER::get, index -> index));
        assertThat(foreignKeys)
                .as("cleanup は information_schema の全 billing FK に対する逆順であること")
                .allSatisfy(foreignKey -> assertThat(cleanupRanks.get(foreignKey.childTable()))
                        .isLessThan(cleanupRanks.get(foreignKey.parentTable())));
    }

    private static List<ForeignKey> queryForeignKeys(Connection connection) throws Exception {
        java.util.ArrayList<ForeignKey> result = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT DISTINCT table_name, referenced_table_name
                       FROM information_schema.key_column_usage
                      WHERE table_schema = DATABASE()
                        AND referenced_table_name IS NOT NULL
                        AND table_name IN ('billing_contracts', 'billing_customers', 'billing_price_versions',
                                           'billing_price_band_versions', 'billing_quotes',
                                           'billing_change_previews', 'billing_contract_changes',
                                           'billing_contract_operations',
                                           'active_billing_contract_operation_pointers',
                                           'billing_membership_price_adjustments',
                                           'billing_customer_migrations', 'billing_invoices',
                                           'billing_invoice_adjustments', 'billing_invoice_lines',
                                           'billing_return_state_nonces', 'billing_api_idempotencies',
                                           'active_contract_pointers', 'stripe_webhook_events')
                     """)) {
            while (rows.next()) {
                result.add(new ForeignKey(rows.getString(1), rows.getString(2)));
            }
        }
        return List.copyOf(result);
    }

    private static void assertColumnLength(Connection connection, String table, String column, int expected)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT character_maximum_length
                       FROM information_schema.columns
                      WHERE table_schema = DATABASE()
                        AND table_name = '%s'
                        AND column_name = '%s'
                     """.formatted(table, column))) {
            assertThat(result.next()).as("%s.%s があること", table, column).isTrue();
            assertThat(result.getInt(1)).isEqualTo(expected);
        }
    }

    private static void assertColumnsExist(Connection connection, String table, String... columns) throws Exception {
        Set<String> actual = queryStrings(connection, """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = '%s'
                """.formatted(table));
        assertThat(actual).as("%s の V196 拡張列", table).contains(columns);
    }

    private static Set<String> queryStrings(Connection connection, String sql) throws Exception {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return Set.copyOf(result);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private record ForeignKey(String childTable, String parentTable) {
    }
}
