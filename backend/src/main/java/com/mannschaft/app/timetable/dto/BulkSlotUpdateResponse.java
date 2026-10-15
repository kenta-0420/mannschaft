package com.mannschaft.app.timetable.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class BulkSlotUpdateResponse {
    private final int updatedCount;
    private final int totalSlots;
}
