package com.mannschaft.app.billing.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 価格改定戦役（price-revisions）第1隊: {@code billing_tax_codes} migration の静的照合（試練・A群）。
 *
 * <p>Flyway/Testcontainers を起動せず、{@code src/main/resources/db/migration} 配下の SQL テキストを
 * 直接検証する（{@link com.mannschaft.app.billing.BillingUniqueConstraintDdlAlignmentGuardTest} と
 * 同型の静的照合手法）。migration がまだ存在しないため、現状は全ケースが red で落ちる
 * （ファイルが1件も見つからず {@code assertThat(...).isNotEmpty()} が失敗する）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-1〜AC-3。</p>
 */
@DisplayName("billing_tax_codes migration 静的照合（AC-1〜AC-3）")
class BillingTaxCodeMigrationStaticGuardTest {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");

    /** {@code CREATE TABLE billing_tax_codes} を含む migration を探す（バージョン番号は決め打たない）。 */
    private String taxCodeMigrationSql() {
        List<String> matched = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MIGRATION_DIR, "*.sql")) {
            for (Path p : stream) {
                String sql = Files.readString(p);
                if (sql.toLowerCase().contains("create table billing_tax_codes")) {
                    matched.add(sql);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(matched)
                .as("billing_tax_codes を作る migration が db/migration 配下に存在すること（決定5・1本目）")
                .hasSize(1);
        return matched.get(0);
    }

    @Test
    @DisplayName("AC-1: 決定6の列定義（chk_btc_rate 含む）で billing_tax_codes を作る")
    void ac1_createsTableWithDecision6Columns() {
        String sql = taxCodeMigrationSql().toLowerCase();
        assertThat(sql).contains("code").contains("varchar(64)");
        assertThat(sql).contains("display_name").contains("varchar(64)");
        assertThat(sql).contains("rate_basis_points");
        assertThat(sql).contains("stripe_tax_code").contains("varchar(64)");
        assertThat(sql).contains("valid_from").contains("datetime(6)");
        assertThat(sql).contains("valid_until");
        assertThat(sql).contains("enabled");
        assertThat(sql).contains("deleted_at");
        assertThat(sql).contains("chk_btc_rate");
        // rate_basis_points BETWEEN 0 AND 10000 の意図（表記ゆれを許容し between/0/10000 の共存で確認）
        assertThat(sql).contains("between").contains("10000");
        assertThat(sql).contains("uk_btc_code_from");
        assertThat(sql).contains("code").contains("valid_from");
    }

    @Test
    @DisplayName("AC-2: 初期 seed（JP_STANDARD_10=1000bp / JP_REDUCED_8=800bp、valid_from=1970-01-01）")
    void ac2_seedsInitialTaxCodes() {
        String sql = taxCodeMigrationSql();
        assertThat(sql).contains("JP_STANDARD_10");
        assertThat(sql).contains("JP_REDUCED_8");
        assertThat(sql).contains("1970-01-01 00:00:00.000000");
        // 1000bp / 800bp が INSERT 文中に現れること（他の意味の1000/800との誤検出を避けるため両方要求）
        Matcher m1000 = Pattern.compile("1000").matcher(sql);
        Matcher m800 = Pattern.compile("(?<!1)800(?!0)").matcher(sql);
        assertThat(m1000.find()).as("rate_basis_points=1000 (JP_STANDARD_10) が seed に含まれること").isTrue();
        assertThat(m800.find()).as("rate_basis_points=800 (JP_REDUCED_8) が seed に含まれること").isTrue();
    }

    @Test
    @DisplayName("AC-3: 税コード専用ロック行 __TAX_CODE_LOCK__（enabled=false）を1行 seed する")
    void ac3_seedsTaxCodeLockRow() {
        String sql = taxCodeMigrationSql();
        assertThat(sql).contains("__TAX_CODE_LOCK__");
        // ロック行の直近の値として enabled=false（0/false 表記どちらでも可）が現れることを緩く確認
        int idx = sql.indexOf("__TAX_CODE_LOCK__");
        String around = sql.substring(idx, Math.min(sql.length(), idx + 400)).toLowerCase();
        assertThat(around).satisfiesAnyOf(
                s -> assertThat(s).contains(", false"),
                s -> assertThat(s).contains(", 0"),
                s -> assertThat(s).contains("=false"),
                s -> assertThat(s).contains("= false"));
    }

    @Test
    @DisplayName("決定5番人: 本戦役の新規 Flyway migration は3本に限る（tax_codes / provision_tracking_columns / stripe_products）")
    void decision5_exactlyThreeNewMigrations() {
        List<String> names;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MIGRATION_DIR, "*.sql")) {
            names = new ArrayList<>();
            for (Path p : stream) {
                String sql = Files.readString(p);
                String lower = sql.toLowerCase();
                if (lower.contains("create table billing_tax_codes")
                        || lower.contains("create table billing_stripe_products")
                        || (lower.contains("provision_attempts") && lower.contains("alter table billing_price_band_versions"))) {
                    names.add(p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(names)
                .as("価格改定戦役の新規 migration は決定5の3本のみ（%s）", names.stream().collect(Collectors.joining(", ")))
                .hasSize(3);
    }
}
