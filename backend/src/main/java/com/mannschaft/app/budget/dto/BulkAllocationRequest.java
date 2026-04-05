package com.mannschaft.app.budget.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 予算配分一括リクエスト。
 */
public record BulkAllocationRequest(

        @NotNull
        Long fiscalYearId,

        @NotEmpty
        @Valid
        List<AllocationRequest> allocations
) {
}
