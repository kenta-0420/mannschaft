package com.mannschaft.app.repairplan.dto;

import com.mannschaft.app.repairplan.engine.GenerationMeter;
import com.mannschaft.app.repairplan.engine.YearlyBalance;

import java.util.List;
import java.util.Map;

public record SimulateRepairPlanResponse(
        String engineVersion,
        String contentSha256,
        List<YearlyBalance> yearlyBalances,
        Integer depletionYear,
        Map<String, GenerationMeter> generationMeters,
        List<String> warnings
) {}
