package com.mannschaft.app.repairplan.dto;

import java.util.UUID;

/** コルクボードへのピン止め結果 DTO（F08.8 Phase 2）。 */
public record PinToCorkboardResponse(
        UUID scenarioId,
        Long pinnedCorkboardId
) {}
