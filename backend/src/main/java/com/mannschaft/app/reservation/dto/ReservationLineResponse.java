package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 予約ラインレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationLineResponse {

    Long id;
    Long teamId;
    LineMetaDto meta;
    ReservationLineAuditDto audit;

    public record LineMetaDto(String name, String description, Integer displayOrder, Boolean isActive, Long defaultStaffUserId) {}

    public record ReservationLineAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
