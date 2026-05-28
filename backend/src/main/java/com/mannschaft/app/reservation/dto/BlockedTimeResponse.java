package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ブロック時間レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class BlockedTimeResponse {

    Long id;
    Long teamId;
    TimeSlotDto timeSlot;
    BlockedAuditDto audit;

    public record TimeSlotDto(LocalDate blockedDate, LocalTime startTime, LocalTime endTime) {}

    public record BlockedAuditDto(String reason, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
