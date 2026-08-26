package com.mannschaft.app.advertising.migration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>F09.19.5 既存データ番人テスト（V144.005 / V144.006 = ad_campaigns の scope 化 Expand→Contract）</b>。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §5.2（V144.005/006）。
 * {@code ad_campaigns.advertiser_organization_id}（organization 直結）を、同一 advertising ドメインの
 * {@code advertiser_accounts.id} 直結（{@code advertiser_account_id}）へ付け替える。
 * V144.005 は列追加 + backfill（{@code scope_type='ORGANIZATION' AND scope_id=advertiser_organization_id
 * AND deleted_at IS NULL} で JOIN）+ NOT NULL 昇格 + FK。V144.006 は旧列/インデックス削除（Contract）。</p>
 *
 * <p>金型: {@code FlywayExistingDataAdMessagingChannelPlacementMigrationTest} /
 * {@code SharedFileLinkFlywayColumnIT}。Spring コンテキストを起動せず、Testcontainers の実 MySQL 8.0 に
 * {@link Flyway} を Java API で直接実行する。<b>既存 {@code ad_campaigns} 行を旧スキーマ（列追加前）で seed し、
 * 残りのマイグレーションを適用</b>する「既存データ経路」を再現する（{@code FlywayFromScratchMigrationTest}
 * の空 DB では既存行由来の backfill / NOT NULL 昇格の退行を見逃す）。</p>
 *
 * <p><b>orphan 行番人（正本 §5.2 の前提条件検証）</b>: 対応する {@code advertiser_accounts} が
 * 論理削除済み等で backfill JOIN に一致しない孤児キャンペーンがあると、{@code advertiser_account_id} が
 * NULL のまま残り NOT NULL 昇格で失敗する。マイグレーションが<b>孤児を黙って壊さず失敗する</b>ことを検証し、
 * 「orphan 0 件（事前に account 復元 or キャンペーン削除で解消）」という適用前提を番人する。</p>
 *
 * <p><b>red 分類（実装不在）</b>: 試練時点では V144.005/006 が未作成のため、残りマイグレーション適用後も
 * {@code advertiser_account_id} 列が作られず backfill も起きない。したがって
 * (1) 正常系は列不在で red、(2) orphan 系は「失敗するはずの migrate が成功してしまう」ため red。
 * 出陣で V144.005/006 を新規タイムスタンプ（{@code date -u '+%Y%m%d%H%M%S'} で採番。既使用
 * {@code V144.20260707124155〜58} / {@code V144.20260707124540} と衝突しない値）で作成すると green。</p>
 */
@EnabledIf("com.mannschaft.app.advertising.migration.FlywayExistingDataAdCampaignAdvertiserAccountMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ ad_campaigns advertiser_account_id 移行（V144.005/006）番人テスト")
class FlywayExistingDataAdCampaignAdvertiserAccountMigrationTest {

    /**
     * {@code advertiser_account_id} 追加（V144.005）の直前バージョン。
     * 本書執筆時点で major 144 の最大は {@code 144.20260707124540}（別セッションの event_checkins 移行）。
     * V144.005 は必ずこれより後のタイムスタンプで採番されるため、ここまで適用してから
     * {@code advertiser_account_id} 列の無い既存 {@code ad_campaigns} 行を seed する。
     */
    private static final String PRE_SCOPE_TARGET = "144.20260707124540";

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private MySQLContainer<?> newMySql() {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("mannschaft_adcampaign_scope")
                .withUsername("test")
                .withPassword("test")
                .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 正常系: 既存キャンペーンが advertiser_account_id に backfill され旧列が消える
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac5_4_ddl: 既存キャンペーンが移行後に advertiser_account_id へ backfill され旧列(advertiser_organization_id)が消える")
    void 既存キャンペーンがadvertiserAccountIdへbackfillされ旧列が消える() throws Exception {
        try (MySQLContainer<?> mysql = newMySql()) {
            mysql.start();

            // given: V144.005 直前まで適用
            migrateTo(mysql, PRE_SCOPE_TARGET);

            long orgId = 700100L;
            long accountId;
            long campaignId;
            try (Connection conn = connect(mysql); Statement st = conn.createStatement()) {
                // sanity: この時点で advertiser_account_id 列は存在しない（旧スキーマの証明）
                assertThat(columnExists(conn, "ad_campaigns", "advertiser_account_id"))
                        .as("V" + PRE_SCOPE_TARGET + " 時点では advertiser_account_id 列が存在しないこと").isFalse();

                // 親テーブルの全 NOT NULL 列を組むのは brittle なため FK チェックをセッション限定で無効化し最小 INSERT。
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                accountId = insertAdvertiserAccount(conn, "ORGANIZATION", orgId, false, "組織広告主(有効)");
                campaignId = insertCampaign(conn, orgId, "組織キャンペーン(移行対象)");
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            // when: 残りのマイグレーション（V144.005/006 含む想定）を適用
            MigrateResult rest = migrateAll(mysql);
            assertThat(rest.success)
                    .as("既存キャンペーン行があっても scope 化移行を含む残りの適用が成功すること").isTrue();

            // then
            try (Connection conn = connect(mysql)) {
                assertThat(columnExists(conn, "ad_campaigns", "advertiser_account_id"))
                        .as("advertiser_account_id 列が追加されていること（V144.005）").isTrue();

                assertThat(scalarLong(conn,
                        "SELECT advertiser_account_id FROM ad_campaigns WHERE id = " + campaignId))
                        .as("既存キャンペーンが scope 一致 advertiser_accounts.id に backfill されること")
                        .isEqualTo(accountId);

                assertThat(columnExists(conn, "ad_campaigns", "advertiser_organization_id"))
                        .as("旧列 advertiser_organization_id が Contract(V144.006)で削除されていること").isFalse();
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // orphan 番人: 論理削除済み広告主に紐づく孤児キャンペーンがあると移行は失敗する
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac5_4_orphan: 論理削除済み広告主に紐づく孤児キャンペーンがあると NOT NULL 昇格で移行が失敗する（前提条件番人）")
    void 孤児キャンペーンがあると移行が失敗する() throws Exception {
        try (MySQLContainer<?> mysql = newMySql()) {
            mysql.start();

            migrateTo(mysql, PRE_SCOPE_TARGET);

            long orphanOrgId = 700200L;
            try (Connection conn = connect(mysql); Statement st = conn.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                // 論理削除済み（deleted_at 非 NULL）の広告主 → backfill JOIN(deleted_at IS NULL) に一致しない
                insertAdvertiserAccount(conn, "ORGANIZATION", orphanOrgId, true, "組織広告主(論理削除)");
                insertCampaign(conn, orphanOrgId, "孤児キャンペーン");
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            // when/then: 孤児行は advertiser_account_id が NULL のまま残り、NOT NULL 昇格で失敗する
            ThrowingCallable migrateWithOrphan = () -> migrateAll(mysql);
            assertThatThrownBy(migrateWithOrphan)
                    .as("孤児キャンペーンを黙って壊さず、NOT NULL 昇格でマイグレーションが失敗すること"
                            + "（適用前提: orphan 0 件。事前に account 復元 or キャンペーン削除で解消する運用）")
                    .isInstanceOf(Exception.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Flyway ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void migrateTo(MySQLContainer<?> mysql, String target) {
        MigrateResult r = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
        assertThat(r.success).as("V" + target + " までの適用が成功すること").isTrue();
    }

    private MigrateResult migrateAll(MySQLContainer<?> mysql) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    private Connection connect(MySQLContainer<?> mysql) throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    // ═════════════════════════════════════════════════════════════════════
    // seed ヘルパー（FK チェック無効化下の最小 INSERT）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * advertiser_accounts を 1 行 seed し id を返す。
     * organization_id は V67.025 で NULL 許可降格済みのため省略する。
     */
    private long insertAdvertiserAccount(Connection conn, String scopeType, long scopeId,
                                         boolean softDeleted, String companyName) throws Exception {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO advertiser_accounts "
                            + "(scope_type, scope_id, status, company_name, contact_email, billing_method, "
                            + " credit_limit, deleted_at, created_at, updated_at) "
                            + "VALUES ('" + scopeType + "', " + scopeId + ", 'ACTIVE', '" + companyName + "', "
                            + "'ads@example.com', 'INVOICE', 100000, " + deletedAt + ", NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).as("advertiser_accounts の生成キーが取れること").isTrue();
                return keys.getLong(1);
            }
        }
    }

    /** advertiser_organization_id 直結（旧スキーマ）の ad_campaigns を 1 行 seed し id を返す。 */
    private long insertCampaign(Connection conn, long advertiserOrganizationId, String name) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO ad_campaigns "
                            + "(advertiser_organization_id, name, status, pricing_model, created_at, updated_at) "
                            + "VALUES (" + advertiserOrganizationId + ", '" + name + "', 'ACTIVE', 'CPM', NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).as("ad_campaigns の生成キーが取れること").isTrue();
                return keys.getLong(1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 検査ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /** 単一行・単一列の long 値。 */
    private static long scalarLong(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("クエリが 1 行返すこと: " + sql).isTrue();
            return rs.getLong(1);
        }
    }
}
