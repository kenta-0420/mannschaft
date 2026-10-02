package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6a: {@link BillingOperationKind} / {@link BillingOperationStatus} の値集合が
 * V196 の CHECK 制約と完全一致することを機械担保する。
 *
 * <p><b>なぜ V196 の SQL ファイルを実読するのか</b>: {@code test} profile の schema は Entity 由来の
 * DDL 自動生成で構築され、V196 の {@code CHECK} 制約は再現されない
 * （{@code feedback_test_profile_ddl_create_skips_flyway_seed} 前例と同型の罠）。したがって
 * Entity と DDL の CHECK 制約の整合は、DB へ実際に投入して確認する経路が存在せず、
 * migration ファイルのテキストを直接読んで照合するほかない。</p>
 *
 * <p>{@code step} 列（{@link BillingOperationStep}）は V196 に CHECK 制約が無いため対象外
 * （値集合は Javadoc のみで固定・{@link BillingOperationStep} 参照）。</p>
 */
@DisplayName("PR6a billing_contract_operations: Entity enum と V196 CHECK 制約の整合")
class BillingContractOperationEntityDdlAlignmentTest {

    private static final Path V196_PATH =
            Paths.get("src/main/resources/db/migration/V196.20260831142049__expand_billing_center.sql");

    private static String readMigrationSql() {
        try {
            return Files.readString(V196_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("V196 migration ファイルが読めない: " + V196_PATH, e);
        }
    }

    /** {@code CONSTRAINT <name> CHECK (... IN ('A','B',...))} から値集合を抽出する。 */
    private static Set<String> extractCheckValues(String sql, String constraintName) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(constraintName) + "\\s+CHECK\\s*\\([^)]*?IN\\s*\\(([^)]*)\\)",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            throw new AssertionError("V196 に制約 " + constraintName + " の IN(...) 値集合が見つからない");
        }
        String inner = matcher.group(1);
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .map(s -> s.replace("'", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> enumNames(Class<? extends Enum<?>> enumType) {
        return Stream.of(enumType.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("BillingOperationKind の値集合は chk_bco_kind と完全一致する")
    void kindMatchesCheckConstraint() {
        Set<String> ddlValues = extractCheckValues(readMigrationSql(), "chk_bco_kind");
        Set<String> enumValues = enumNames(BillingOperationKind.class);

        assertThat(enumValues)
                .as("BillingOperationKind の値集合は V196 chk_bco_kind と完全一致すること")
                .containsExactlyInAnyOrderElementsOf(ddlValues);
    }

    @Test
    @DisplayName("BillingOperationStatus の値集合は chk_bco_status と完全一致する")
    void statusMatchesCheckConstraint() {
        Set<String> ddlValues = extractCheckValues(readMigrationSql(), "chk_bco_status");
        Set<String> enumValues = enumNames(BillingOperationStatus.class);

        assertThat(enumValues)
                .as("BillingOperationStatus の値集合は V196 chk_bco_status と完全一致すること")
                .containsExactlyInAnyOrderElementsOf(ddlValues);
    }

    @Test
    @DisplayName("V196 の step 列に CHECK 制約が無いことを確認する（BillingOperationStep は Javadoc 固定のみ）")
    void stepColumnHasNoCheckConstraintInDdl() {
        String sql = readMigrationSql();
        int tableStart = sql.indexOf("CREATE TABLE billing_contract_operations");
        assertThat(tableStart).as("billing_contract_operations の CREATE TABLE が見つかること").isNotNegative();
        int tableEnd = sql.indexOf(") ENGINE=InnoDB", tableStart);
        assertThat(tableEnd).as("billing_contract_operations の CREATE TABLE 終端が見つかること").isNotNegative();
        String tableDdl = sql.substring(tableStart, tableEnd);

        assertThat(tableDdl).contains("step VARCHAR(32) NOT NULL");
        assertThat(tableDdl).doesNotContain("chk_bco_step");
    }
}
