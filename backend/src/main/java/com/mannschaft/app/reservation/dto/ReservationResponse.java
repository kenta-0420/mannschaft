package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 予約レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationResponse {

    Long id;
    ReservationIdentifierDto identifier;
    ReservationStatusDto status;
    CancellationDto cancellation;
    NotesDto notes;
    ReservationAuditDto audit;

    public record ReservationIdentifierDto(Long reservationSlotId, Long lineId, Long teamId, Long userId) {}

    public record ReservationStatusDto(String status, LocalDateTime bookedAt, LocalDateTime confirmedAt, LocalDateTime completedAt) {}

    public record CancellationDto(LocalDateTime cancelledAt, String cancelReason, String cancelledBy) {}

    public record NotesDto(String userNote, String adminNote) {}

    public record ReservationAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
