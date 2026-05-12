package com.mannschaft.app.repairplan.engine;

import java.util.List;
import java.util.Map;

/** シミュレーション計算結果（不変）。 */
public record SimulationResult(
        String engineVersion,
        String contentSha256,
        List<YearlyBalance> yearlyBalances,
        Integer depletionYear,              // null = 期間内に枯渇なし
        Map<String, GenerationMeter> generationMeters, // "20s","30s",...,"70s_plus"
        List<String> warnings
) {}
