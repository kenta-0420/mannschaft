package com.mannschaft.app.repairplan.engine;

/** 特定年代帯の枯渇影響判定（不変）。 */
public record GenerationMeter(
        int currentAvgAge,
        int ageAtDepletion,
        GenerationSeverity impact
) {}
