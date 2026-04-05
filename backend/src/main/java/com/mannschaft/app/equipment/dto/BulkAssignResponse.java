package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括貸出レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkAssignResponse {

    private final Integer createdCount;
    private final Integer totalAssignedQuantity;
    private final String equipmentStatus;
    private final Integer availableQuantity;
    private final List<BulkAssignEntry> assignments;

    /**
     * 一括貸出の各エントリレスポンス。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BulkAssignEntry {
        private final Long assignmentId;
        private final Long assignedToUserId;
        private final Integer quantity;
    }
}
