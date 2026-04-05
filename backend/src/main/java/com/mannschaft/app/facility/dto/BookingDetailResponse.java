package com.mannschaft.app.facility.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設予約詳細レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BookingDetailResponse {

    private final Long id;
    private final Long facilityId;
    private final String facilityName;
    private final Long bookedBy;
    private final Long createdByAdmin;
    private final LocalDate bookingDate;
    private final LocalDate checkOutDate;
    private final Integer stayNights;
    private final LocalTime timeFrom;
    private final LocalTime timeTo;
    private final Integer slotCount;
    private final String purpose;
    private final Integer attendeeCount;
    private final BigDecimal usageFee;
    private final BigDecimal equipmentFee;
    private final BigDecimal totalFee;
    private final String status;
    private final String adminComment;
    private final Long approvedBy;
    private final LocalDateTime approvedAt;
    private final LocalDateTime checkedInAt;
    private final LocalDateTime completedAt;
    private final LocalDateTime cancelledAt;
    private final Long cancelledBy;
    private final String cancellationReason;
    private final List<BookingEquipmentResponse> equipment;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * 予約備品レスポンス。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BookingEquipmentResponse {
        private final Long equipmentId;
        private final String equipmentName;
        private final Integer quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal subtotal;
    }
}
