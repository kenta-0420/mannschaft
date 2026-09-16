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
 * Billing Center PR6b-1: {@link BillingContractChangeKind} / {@link BillingContractChangeStatus}
 * の値集合が V196 の CHECK 制約と完全一致することを機械担保する。
 *
 * <p><b>なぜ V196 の SQL ファイルを実読するのか</b>: {@code test} profile の schema は Entity 由来の
 * DDL 自動生成で構築され、V196 の {@code CHECK} 制約は再現されない
 * （{@code feedback_test_profile_ddl_create_skips_flyway_seed} 前例と同型の罠）。したがって
 * Entity と DDL の CHECK 制約の整合は、DB へ実際に投入して確認する経路が存在せず、
 * migration ファイルのテキストを直接読んで照合するほかない
 * （{@code BillingContractOperationEntityDdlAlignmentTest} が金型）。</p>
 */
@DisplayName("PR6b-1 billing_contract_changes: Entity enum と V196 CHECK 制約の整合")
class BillingContractChangeEntityDdlAlignmentTest {

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
    @DisplayName("BillingContractChangeKind の値集合は chk_bcc_kind と完全一致する")
    void kindMatchesCheckConstraint() {
        Set<String> ddlValues = extractCheckValues(readMigrationSql(), "chk_bcc_kind");
        Set<String> enumValues = enumNames(BillingContractChangeKind.class);

        assertThat(enumValues)
                .as("BillingContractChangeKind の値集合は V196 chk_bcc_kind と完全一致すること")
                .containsExactlyInAnyOrderElementsOf(ddlValues);
    }

    @Test
    @DisplayName("BillingContractChangeStatus の値集合は chk_bcc_status（7値）と完全一致する")
    void statusMatchesCheckConstraint() {
        Set<String> ddlValues = extractCheckValues(readMigrationSql(), "chk_bcc_status");
        Set<String> enumValues = enumNames(BillingContractChangeStatus.class);

        assertThat(ddlValues).hasSize(7);
        assertThat(enumValues)
                .as("BillingContractChangeStatus の値集合は V196 chk_bcc_status と完全一致すること")
                .containsExactlyInAnyOrderElementsOf(ddlValues);
    }

    @Test
    @DisplayName("chk_bcc_refs の4分岐がV196に存在し、UPGRADE/DOWNGRADEの参照条件を規定していることを確認する")
    void refsCheckConstraintDefinesFourBranches() {
        String sql = readMigrationSql();
        int idx = sql.indexOf("chk_bcc_refs");
        assertThat(idx).as("chk_bcc_refs が見つかること").isNotNegative();
        int end = sql.indexOf(')', sql.indexOf("CHECK", idx));
        // CHECK 全体（複数の閉じ括弧をまたぐため、末尾の ") )" 付近まで広めに取る
        String fragment = sql.substring(idx, Math.min(sql.length(), idx + 700));

        assertThat(fragment).contains("kind = 'UPGRADE' AND stripe_schedule_ref IS NULL");
        assertThat(fragment).contains("kind = 'DOWNGRADE' AND status = 'CREATING_SCHEDULE' AND stripe_schedule_ref IS NULL");
        assertThat(fragment)
                .contains("kind = 'DOWNGRADE' AND status IN ('SCHEDULED','APPLIED') AND stripe_schedule_ref IS NOT NULL");
        assertThat(fragment).contains("kind = 'DOWNGRADE' AND status IN ('FAILED','CANCELLED')");
    }
}
