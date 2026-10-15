package com.mannschaft.app.budget.dto;

import java.util.List;

/**
 * 予算配分一括レスポンス。
 */
public record BulkAllocationResponse(
        int totalCount,
        int createdCount,
        int updatedCount,
        List<AllocationResponse> allocations
) {
}
