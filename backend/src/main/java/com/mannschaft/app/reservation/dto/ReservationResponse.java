package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 予約レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationResponse {

    Long id;
    ReservationIdentifierDto identifier;
    SlotSummaryDto slot;
    ReservationStatusDto status;
    CancellationDto cancellation;
    NotesDto notes;
    ReservationAuditDto audit;

    public record ReservationIdentifierDto(Long reservationSlotId, Long lineId, Long teamId, Long userId, String userName) {}

    public record SlotSummaryDto(String lineName, String title, LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}

    public record ReservationStatusDto(String status, LocalDateTime bookedAt, LocalDateTime confirmedAt, LocalDateTime completedAt) {}

    public record CancellationDto(LocalDateTime cancelledAt, String cancelReason, String cancelledBy) {}

    public record NotesDto(String userNote, String adminNote) {}

    public record ReservationAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
