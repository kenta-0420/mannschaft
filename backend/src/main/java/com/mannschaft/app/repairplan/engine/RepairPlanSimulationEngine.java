package com.mannschaft.app.repairplan.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * 修繕積立金枯渇シミュレーション計算エンジン。
 *
 * <p>DB・時刻・外部依存ゼロの純粋計算クラス。
 * Service 層が初期残高・修繕費集計を渡し、本クラスが年度収支を計算する。</p>
 */
@Component
public class RepairPlanSimulationEngine {

    public static final String ENGINE_VERSION = "v1.0.0";

    private static final MathContext MC = new MathContext(15, RoundingMode.HALF_UP);

    /**
     * シミュレーションを実行して結果を返す。
     *
     * @param params       入力パラメータ（Service 層が DB から取得して詰めた値）
     * @param baselineYear 基準年（baseline_at の年）
     */
    public SimulationResult simulate(SimulationParams params, int baselineYear) {
        List<YearlyBalance> yearlyBalances = new ArrayList<>();
        BigDecimal balance = params.initialBalance();
        Integer depletionYear = null;
        List<String> warnings = new ArrayList<>();

        // baseline_at が 30 日以上前の場合は警告
        LocalDate baselineDate = params.baselineAt().toLocalDate();
        long daysOld = java.time.temporal.ChronoUnit.DAYS.between(baselineDate, LocalDate.now());
        if (daysOld > 30) {
            warnings.add("calc_baseline_age_days: " + daysOld);
        }

        for (int t = 0; t < params.scenarioHorizonYears(); t++) {
            int year = baselineYear + t;

            // 収入: 月額 × 戸数 × 12 × (1 + reserve_inflation_rate)^t
            BigDecimal income = params.monthlyFee()
                    .multiply(BigDecimal.valueOf(params.dwellingUnits()), MC)
                    .multiply(BigDecimal.valueOf(12), MC)
                    .multiply(BigDecimal.ONE.add(params.reserveInflationRate(), MC).pow(t, MC), MC);

            // 修繕費: repair_plan_items 集計 × (1 + cpi)^(t + deferral_years)
            BigDecimal basePlannedExpense = params.yearlyPlannedExpenses()
                    .getOrDefault(year + params.deferralYears(), BigDecimal.ZERO);
            BigDecimal plannedExpense = basePlannedExpense
                    .multiply(BigDecimal.ONE.add(params.cpiInflationRate(), MC).pow(t, MC), MC);

            // 借入金返済
            BigDecimal loanRepayment = calcLoanRepayment(params, t);

            // 残高更新
            balance = balance
                    .add(income, MC)
                    .subtract(plannedExpense, MC)
                    .subtract(params.fixedManagementCostYearly(), MC)
                    .subtract(loanRepayment, MC);

            yearlyBalances.add(new YearlyBalance(year, balance.setScale(0, RoundingMode.HALF_UP),
                    income.setScale(0, RoundingMode.HALF_UP),
                    plannedExpense.setScale(0, RoundingMode.HALF_UP),
                    BigDecimal.ZERO));

            if (depletionYear == null && balance.compareTo(BigDecimal.ZERO) < 0) {
                depletionYear = year;
            }
        }

        Map<String, GenerationMeter> meters = calcGenerationMeters(depletionYear, baselineYear);
        String contentSha256 = calcContentSha256(params, yearlyBalances, depletionYear);

        return new SimulationResult(ENGINE_VERSION, contentSha256, yearlyBalances,
                depletionYear, meters, warnings);
    }

    // --- 世代別メーター ---

    private Map<String, GenerationMeter> calcGenerationMeters(Integer depletionYear, int baselineYear) {
        Map<String, GenerationMeter> meters = new LinkedHashMap<>();
        int[][] generations = {{20, 25}, {30, 35}, {40, 45}, {50, 55}, {60, 65}, {70, 75}};
        String[] keys = {"20s", "30s", "40s", "50s", "60s", "70s_plus"};

        for (int i = 0; i < generations.length; i++) {
            int avgAge = generations[i][1];
            int ageAtDepletion = depletionYear == null ? 999 : avgAge + (depletionYear - baselineYear);
            int yearsUntilDepletion = depletionYear == null ? Integer.MAX_VALUE : depletionYear - baselineYear;
            GenerationSeverity severity = calcSeverity(i, yearsUntilDepletion);
            meters.put(keys[i], new GenerationMeter(avgAge, Math.min(ageAtDepletion, 120), severity));
        }
        return meters;
    }

    private GenerationSeverity calcSeverity(int genIndex, int yearsUntilDepletion) {
        return switch (genIndex) {
            case 0, 1 -> // 20s, 30s
                yearsUntilDepletion <= 30 ? GenerationSeverity.SEVERE
                    : yearsUntilDepletion <= 50 ? GenerationSeverity.MODERATE
                    : GenerationSeverity.SAFE;
            case 2, 3 -> // 40s, 50s
                yearsUntilDepletion <= 10 ? GenerationSeverity.SEVERE
                    : yearsUntilDepletion <= 30 ? GenerationSeverity.MODERATE
                    : GenerationSeverity.SAFE;
            default ->  // 60s, 70s+
                yearsUntilDepletion <= 10 ? GenerationSeverity.MODERATE
                    : GenerationSeverity.SAFE;
        };
    }

    // --- SHA-256 ---

    public String calcContentSha256(SimulationParams params, List<YearlyBalance> balances, Integer depletionYear) {
        String raw = params.toString() + balances.toString() + depletionYear + ENGINE_VERSION;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // --- 借入金返済計算（元利均等） ---

    private BigDecimal calcLoanRepayment(SimulationParams params, int t) {
        if (params.loanPrincipal().compareTo(BigDecimal.ZERO) == 0
                || params.loanTermYears() == 0
                || t >= params.loanTermYears()) {
            return BigDecimal.ZERO;
        }
        // 元利均等返済: P * r / (1 - (1+r)^-n)
        BigDecimal r = params.loanInterestRate().divide(BigDecimal.valueOf(12), MC);
        int n = params.loanTermYears() * 12;
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return params.loanPrincipal().divide(BigDecimal.valueOf(params.loanTermYears()), MC);
        }
        BigDecimal monthly = params.loanPrincipal()
                .multiply(r, MC)
                .divide(BigDecimal.ONE.subtract(BigDecimal.ONE.add(r, MC).pow(-n, MC), MC), MC);
        return monthly.multiply(BigDecimal.valueOf(12), MC); // 年額
    }
}
