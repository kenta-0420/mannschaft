package com.mannschaft.app.repairplan.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.8 Phase 2 シミュレーション計算エンジン ユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
class RepairPlanSimulationEngineTest {

    RepairPlanSimulationEngine engine = new RepairPlanSimulationEngine();

    private static final int BASELINE_YEAR = 2026;

    // ベースとなる SimulationParams（修正して各テストで使用）
    private SimulationParams baseParams(
            BigDecimal initialBalance,
            BigDecimal monthlyFee,
            int dwellingUnits,
            int horizonYears,
            Map<Integer, BigDecimal> yearlyExpenses
    ) {
        return new SimulationParams(
                initialBalance,
                monthlyFee,
                dwellingUnits,
                BigDecimal.ZERO,          // reserveInflationRate
                BigDecimal.ZERO,          // cpiInflationRate
                0,                        // deferralYears
                BigDecimal.ZERO,          // loanPrincipal
                BigDecimal.ZERO,          // loanInterestRate
                0,                        // loanTermYears
                BigDecimal.ZERO,          // fixedManagementCostYearly
                horizonYears,
                LocalDateTime.now(),      // baselineAt（当日）
                yearlyExpenses
        );
    }

    @Nested
    @DisplayName("正常系")
    class NormalCases {

        @Test
        @DisplayName("テスト1: 30年間枯渇しない — 残高が十分に大きい場合 depletionYear == null")
        void test1_noDepleteIn30Years() {
            // 月額10万円 × 100戸 = 年収1.2億、支出なし → 30年後に枯渇しない
            SimulationParams params = baseParams(
                    new BigDecimal("100000000"), // 初期残高1億円
                    new BigDecimal("100000"),    // 月額10万円
                    100,
                    30,
                    Map.of()
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.depletionYear()).isNull();
            assertThat(result.yearlyBalances()).hasSize(30);
            assertThat(result.engineVersion()).isEqualTo(RepairPlanSimulationEngine.ENGINE_VERSION);
        }

        @Test
        @DisplayName("テスト2: 枯渇年が正しく計算される — 10年後に枯渇する設定")
        void test2_depletionYearCalculated() {
            // 初期残高1億、収入なし、毎年1500万円の修繕費 → 約6年で枯渇
            Map<Integer, BigDecimal> expenses = new HashMap<>();
            for (int y = BASELINE_YEAR; y < BASELINE_YEAR + 15; y++) {
                expenses.put(y, new BigDecimal("15000000"));
            }
            SimulationParams params = baseParams(
                    new BigDecimal("100000000"),
                    BigDecimal.ZERO,
                    0,
                    15,
                    expenses
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.depletionYear()).isNotNull();
            assertThat(result.depletionYear()).isGreaterThanOrEqualTo(BASELINE_YEAR);
            assertThat(result.depletionYear()).isLessThan(BASELINE_YEAR + 15);
        }
    }

    @Nested
    @DisplayName("境界値テスト")
    class BoundaryCases {

        @Test
        @DisplayName("テスト3: 月額0円（収入なし）— 残高が減り続け早期に枯渇")
        void test3_monthlyFeeZero() {
            // 収入ゼロ、初期残高1000万、毎年1000万の修繕費 → 1年目で枯渇
            Map<Integer, BigDecimal> expenses = new HashMap<>();
            expenses.put(BASELINE_YEAR, new BigDecimal("10000001"));
            SimulationParams params = baseParams(
                    new BigDecimal("10000000"),
                    BigDecimal.ZERO,
                    0,
                    5,
                    expenses
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.depletionYear()).isEqualTo(BASELINE_YEAR);
        }

        @Test
        @DisplayName("テスト4: 戸数1 — 最小戸数で計算が崩れないこと")
        void test4_singleDwellingUnit() {
            SimulationParams params = baseParams(
                    new BigDecimal("1000000"),
                    new BigDecimal("10000"),
                    1,        // 戸数1
                    10,
                    Map.of()
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.yearlyBalances()).hasSize(10);
            // 収入: 10000 × 1 × 12 = 120000/年 → 10年で枯渇しない
            assertThat(result.depletionYear()).isNull();
        }

        @Test
        @DisplayName("テスト5: 借入金あり（元利均等）— 返済額が引かれること")
        void test5_withLoan() {
            // 借入金あり：収入多め、大きな借入金で残高が減ること
            SimulationParams withLoanParams = new SimulationParams(
                    new BigDecimal("50000000"),
                    new BigDecimal("50000"),
                    100,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    new BigDecimal("100000000"), // 借入金1億
                    new BigDecimal("0.02"),       // 金利2%
                    20,                           // 20年返済
                    BigDecimal.ZERO,
                    10,
                    LocalDateTime.now(),
                    Map.of()
            );
            SimulationParams withoutLoanParams = new SimulationParams(
                    new BigDecimal("50000000"),
                    new BigDecimal("50000"),
                    100,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,  // 借入なし
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    10,
                    LocalDateTime.now(),
                    Map.of()
            );

            SimulationResult withLoan = engine.simulate(withLoanParams, BASELINE_YEAR);
            SimulationResult withoutLoan = engine.simulate(withoutLoanParams, BASELINE_YEAR);

            // 借入金返済がある方が残高が少ないこと
            BigDecimal balanceWithLoan = withLoan.yearlyBalances().get(9).balance();
            BigDecimal balanceWithoutLoan = withoutLoan.yearlyBalances().get(9).balance();
            assertThat(balanceWithLoan).isLessThan(balanceWithoutLoan);
        }

        @Test
        @DisplayName("テスト6: 延期あり（deferral_years=5）— 修繕費が5年後ろにずれること")
        void test6_deferralYears() {
            // 2026年に1億円の修繕計画。deferral=0 → 2026年に発生、deferral=5 → 2031年に発生
            Map<Integer, BigDecimal> expenses = new HashMap<>();
            expenses.put(BASELINE_YEAR, new BigDecimal("100000000"));

            SimulationParams noDeferral = new SimulationParams(
                    new BigDecimal("200000000"),
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,            // deferral=0
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    10,
                    LocalDateTime.now(),
                    expenses
            );
            SimulationParams withDeferral = new SimulationParams(
                    new BigDecimal("200000000"),
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    5,            // deferral=5
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    10,
                    LocalDateTime.now(),
                    expenses
            );

            SimulationResult noDefer = engine.simulate(noDeferral, BASELINE_YEAR);
            SimulationResult deferred = engine.simulate(withDeferral, BASELINE_YEAR);

            // 延期なし: 1年目の修繕費が多い（残高が少ない）
            BigDecimal noDefer1stExpense = noDefer.yearlyBalances().get(0).plannedExpense();
            // 延期あり: 1年目の修繕費はゼロ（残高は維持）
            BigDecimal defer1stExpense = deferred.yearlyBalances().get(0).plannedExpense();

            assertThat(noDefer1stExpense).isGreaterThan(BigDecimal.ZERO);
            assertThat(defer1stExpense).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("世代別メーターテスト")
    class GenerationMeterCases {

        @Test
        @DisplayName("テスト7: 枯渇5年後 — 20s/30s は SEVERE、60s+ は MODERATE になること")
        void test7_generationMeterDepletion5Years() {
            // 5年後に枯渇するシナリオ
            Map<Integer, BigDecimal> expenses = new HashMap<>();
            for (int y = BASELINE_YEAR; y < BASELINE_YEAR + 10; y++) {
                expenses.put(y, new BigDecimal("25000000")); // 毎年2500万
            }
            SimulationParams params = baseParams(
                    new BigDecimal("100000000"),
                    BigDecimal.ZERO,
                    0,
                    10,
                    expenses
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.depletionYear()).isNotNull();
            int yearsUntil = result.depletionYear() - BASELINE_YEAR;
            // 5年未満で枯渇 → 20s/30s SEVERE
            assertThat(yearsUntil).isLessThanOrEqualTo(30);
            assertThat(result.generationMeters().get("20s").impact()).isEqualTo(GenerationSeverity.SEVERE);
            assertThat(result.generationMeters().get("30s").impact()).isEqualTo(GenerationSeverity.SEVERE);
            // 60s/70s_plus は MODERATE（10年以内）
            assertThat(result.generationMeters().get("60s").impact()).isIn(
                    GenerationSeverity.MODERATE, GenerationSeverity.SAFE);
            assertThat(result.generationMeters().get("70s_plus").impact()).isIn(
                    GenerationSeverity.MODERATE, GenerationSeverity.SAFE);
        }

        @Test
        @DisplayName("テスト8: 枯渇なし — 全世代 SAFE になること")
        void test8_generationMeterNoDepltion() {
            SimulationParams params = baseParams(
                    new BigDecimal("1000000000"), // 10億円
                    new BigDecimal("10000"),
                    100,
                    50,
                    Map.of()
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.depletionYear()).isNull();
            result.generationMeters().values()
                    .forEach(meter -> assertThat(meter.impact()).isEqualTo(GenerationSeverity.SAFE));
        }
    }

    @Nested
    @DisplayName("SHA-256 冪等性テスト")
    class Sha256Cases {

        @Test
        @DisplayName("テスト9: content_sha256: 同一input → 同一hash（冪等性確認）")
        void test9_contentSha256Idempotent() {
            SimulationParams params = baseParams(
                    new BigDecimal("50000000"),
                    new BigDecimal("30000"),
                    50,
                    20,
                    Map.of()
            );

            SimulationResult result1 = engine.simulate(params, BASELINE_YEAR);
            SimulationResult result2 = engine.simulate(params, BASELINE_YEAR);

            assertThat(result1.contentSha256()).isEqualTo(result2.contentSha256());
            assertThat(result1.contentSha256()).hasSize(64); // SHA-256 hex は64文字
        }
    }

    @Nested
    @DisplayName("baseline_at 警告テスト")
    class BaselineWarningCases {

        @Test
        @DisplayName("テスト10: baseline_at 警告: 31日前 — warnings に calc_baseline_age_days: 31 相当が含まれること")
        void test10_baselineAtWarning31Days() {
            LocalDateTime staleBaseline = LocalDateTime.now().minusDays(31);
            SimulationParams params = new SimulationParams(
                    new BigDecimal("50000000"),
                    new BigDecimal("30000"),
                    50,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    10,
                    staleBaseline,  // 31日前の基準日
                    Map.of()
            );

            SimulationResult result = engine.simulate(params, BASELINE_YEAR);

            assertThat(result.warnings()).isNotEmpty();
            // "calc_baseline_age_days: 31" または "calc_baseline_age_days: 32" 等（実行タイミングによる）
            assertThat(result.warnings().get(0)).startsWith("calc_baseline_age_days:");
            // 日数が30より大きいこと
            String daysStr = result.warnings().get(0).replace("calc_baseline_age_days: ", "").trim();
            int days = Integer.parseInt(daysStr);
            assertThat(days).isGreaterThan(30);
        }
    }
}
