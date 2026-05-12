package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotNull;

/** コルクボードへのピン止めリクエスト（F08.8 Phase 2）。 */
public record PinToCorkboardRequest(
        @NotNull Long corkboardId
) {}
