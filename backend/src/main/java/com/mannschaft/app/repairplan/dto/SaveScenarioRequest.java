package com.mannschaft.app.repairplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * シナリオ保存リクエスト（F08.8 Phase 2）。
 *
 * <p>{@code name} は null 可。null の場合はサービス層で「シナリオ#N」として自動採番する。</p>
 */
public record SaveScenarioRequest(
        @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @Valid SimulateRepairPlanRequest params
) {}
