package com.mannschaft.app.returnstayplan.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OwnPlan(UUID id, String planType, Boolean isPublished, Location location,
        String timezone, LocalDate startDate, LocalDate endDate, List<Long> teamIds,
        Long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public record Location(String countryCode, String prefectureCode, String regionName) { }
}