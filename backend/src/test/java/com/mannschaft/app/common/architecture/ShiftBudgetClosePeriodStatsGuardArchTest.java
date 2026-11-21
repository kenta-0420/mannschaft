package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 PR-α 殿の決定8（AC49／51 の文数は Hibernate 統計で数える。JdbcTemplate を使うなら別途数えて合算する。
 * 計測漏れのまま緑にしない）の番人（凍結ストアを書き換えない。FreezingArchRule は使わない）。
 *
 * <p>期間解決・日次バッチの候補抽出・3段締めの経路（shiftbudget ドメインと、段2 Post を担う budget の
 * {@code ShiftBudgetMonthlyJournalService}）が JDBC を Hibernate の外（{@code JdbcTemplate}／{@code NamedParameterJdbcTemplate}）から
 * 直接発行すると、{@code Statistics#getPrepareStatementCount} に載らず「文数が増えない」偽の緑になる。そうした依存を持たないことを固定する。
 * 使う必要が生じたら、試練126・128 にその文数の合算を足したうえで本番人の対象から理由付きで外すこと。</p>
 */
@DisplayName("CMP-260909-2152 α 文数計測の番人（締め経路は Hibernate の外で JDBC を発行しない）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ShiftBudgetClosePeriodStatsGuardArchTest {

    private static final String SHIFT_BUDGET = "com.mannschaft.app.shiftbudget";
    private static final String JOURNAL_SERVICE = "com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService";
    private static final List<String> JDBC_ENTRIES = List.of("org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.simple.JdbcClient");

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("試練156 AC49・51（決定8）締め経路のクラスは JdbcTemplate 系に依存しない（Hibernate 統計で文数を漏れなく数えられる）")
    void 締め経路はJdbcTemplateを使わない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : classes) {
            boolean target = c.getPackageName().startsWith(SHIFT_BUDGET) || c.getName().startsWith(JOURNAL_SERVICE);
            if (!target) {
                continue;
            }
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                if (JDBC_ENTRIES.contains(d.getTargetClass().getName())) {
                    violations.add(d.getDescription());
                }
            }
        }
        assertThat(violations).as("Hibernate 統計に載らない JDBC 発行の依存").isEmpty();
    }
}
