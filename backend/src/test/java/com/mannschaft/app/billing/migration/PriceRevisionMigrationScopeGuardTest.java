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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練隊（第3陣）L群: 番人・横断（AC-169・AC-170・AC-173）。
 *
 * <p>決定5（新規 Flyway migration は3本のみ）を静的に固定する。3本とは:</p>
 * <ol>
 *   <li>{@code create billing_tax_codes}</li>
 *   <li>{@code billing_price_band_versions.provision_attempts} / 両テーブルの {@code updated_at} 追加</li>
 *   <li>{@code create billing_stripe_products}</li>
 * </ol>
 *
 * <p>本テストは現時点でいずれの migration も未作成のため、3本とも0件で全ケースが red になる。
 * AC-170（migration に Stripe API 呼び出しが無い）は SQL がプレーンテキストである以上「Stripe
 * SDK 呼び出しを意味する文字列（{@code stripe.com} 等の API 疎通を示唆する語）を含まない」ことで
 * 代替観測する（migration は純粋な DDL/DML であり、そもそも実行時に外部通信を行う仕組みが無いことの
 * 直接的な反証はコード上不可能なため、疑わしい兆候の不在を固定する消極的検体）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定5・L群 AC-169・AC-170・AC-173。</p>
 */
@DisplayName("価格改定migrationの本数・射程固定（AC-169・AC-170・AC-173）")
class PriceRevisionMigrationScopeGuardTest {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");

    private List<Path> allMigrations() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MIGRATION_DIR, "*.sql")) {
            for (Path p : stream) {
                files.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private List<Path> matching(String needleLowerCase) {
        List<Path> matched = new ArrayList<>();
        for (Path p : allMigrations()) {
            try {
                String sql = Files.readString(p).toLowerCase();
                if (sql.contains(needleLowerCase)) {
                    matched.add(p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return matched;
    }

    @Test
    @DisplayName("AC-169: 新規 Flyway migration は決定5の3本のみ（税コード・provision列・stripe_products）")
    void exactlyThreeNewMigrationsExist() {
        List<Path> taxCode = matching("create table billing_tax_codes");
        // "provision_attempts" 単独は V196（billing_price_versions への既存列）にも出現するため、
        // 本戦役の2本目（billing_price_band_versions への ALTER）を一意に特定する複合条件で絞る。
        List<Path> provisionColumns = allMigrations().stream()
                .filter(p -> {
                    try {
                        String sql = Files.readString(p).toLowerCase();
                        return sql.contains("provision_attempts")
                                && sql.contains("alter table billing_price_band_versions");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
        List<Path> stripeProducts = matching("create table billing_stripe_products");

        assertThat(taxCode).as("1本目: billing_tax_codes 作成").hasSize(1);
        assertThat(stripeProducts).as("3本目: billing_stripe_products 作成").hasSize(1);
        assertThat(provisionColumns).as("2本目: provision_attempts 列追加").hasSize(1);
    }

    @Test
    @DisplayName("AC-173: 決定5の3本以外に本戦役由来のDDL追加が無い（billing_price_versions系以外への言及なし）")
    void noOtherDdlBeyondDecision5() {
        List<Path> extra = matching("billing_stripe_product_reconciliation");
        assertThat(extra)
                .as("決定5にない4本目相当の追加テーブルが存在しないこと")
                .isEmpty();

        List<Path> total = matching("create table billing_tax_codes");
        total.addAll(matching("create table billing_stripe_products"));
        assertThat(total).as("新設テーブルは2本（税コード・stripe_products）に限る").hasSize(2);
    }

    @Test
    @DisplayName("AC-170: migration SQL に Stripe API 疎通を示す文字列が含まれない")
    void migrationsDoNotReferenceStripeApiCalls() {
        for (Path p : allMigrations()) {
            try {
                String sql = Files.readString(p).toLowerCase();
                assertThat(sql)
                        .as("%s が Stripe API 呼び出しを示唆する文字列を含まないこと", p.getFileName())
                        .doesNotContain("api.stripe.com")
                        .doesNotContain("stripe.stripeclient")
                        .doesNotContain("stripesecretkey");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
