package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.facility.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 施設予約エンティティ。予約情報を管理する。
 */
@Entity
@Table(name = "facility_bookings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilityBookingEntity extends BaseEntity {

    @Column(nullable = false)
    private Long facilityId;

    @Column(nullable = false)
    private Long bookedBy;

    private Long createdByAdmin;

    @Column(nullable = false)
    private LocalDate bookingDate;

    private LocalDate checkOutDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer stayNights = 0;

    @Column(nullable = false)
    private LocalTime timeFrom;

    @Column(nullable = false)
    private LocalTime timeTo;

    @Column(nullable = false)
    private Integer slotCount;

    @Column(length = 500)
    private String purpose;

    private Integer attendeeCount;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal usageFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal equipmentFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 0)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING_APPROVAL;

    @Column(length = 500)
    private String adminComment;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime checkedInAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    private Long cancelledBy;

    @Column(length = 500)
    private String cancellationReason;

    /**
     * 予約を承認する。
     */
    public void approve(Long approvedBy, String adminComment) {
        this.status = BookingStatus.CONFIRMED;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
        this.adminComment = adminComment;
    }

    /**
     * 予約を却下する。
     */
    public void reject(Long approvedBy, String adminComment) {
        this.status = BookingStatus.REJECTED;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
        this.adminComment = adminComment;
    }

    /**
     * チェックインする。
     */
    public void checkIn() {
        this.status = BookingStatus.CHECKED_IN;
        this.checkedInAt = LocalDateTime.now();
    }

    /**
     * 利用完了する。
     */
    public void complete() {
        this.status = BookingStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 予約をキャンセルする。
     */
    public void cancel(Long cancelledBy, String reason) {
        this.status = BookingStatus.CANCELLED;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = LocalDateTime.now();
        this.cancellationReason = reason;
    }

    /**
     * ノーショーとしてマークする。
     */
    public void markNoShow() {
        this.status = BookingStatus.NO_SHOW;
    }

    /**
     * 予約情報を更新する。
     */
    public void updateBooking(LocalDate bookingDate, LocalDate checkOutDate, Integer stayNights,
                              LocalTime timeFrom, LocalTime timeTo, Integer slotCount,
                              String purpose, Integer attendeeCount) {
        this.bookingDate = bookingDate;
        this.checkOutDate = checkOutDate;
        this.stayNights = stayNights;
        this.timeFrom = timeFrom;
        this.timeTo = timeTo;
        this.slotCount = slotCount;
        this.purpose = purpose;
        this.attendeeCount = attendeeCount;
    }

    /**
     * 料金を設定する。
     */
    public void setFees(BigDecimal usageFee, BigDecimal equipmentFee, BigDecimal totalFee) {
        this.usageFee = usageFee;
        this.equipmentFee = equipmentFee;
        this.totalFee = totalFee;
    }

    /**
     * 承認可能かどうかを判定する。
     */
    public boolean isApprovable() {
        return this.status == BookingStatus.PENDING_APPROVAL;
    }

    /**
     * キャンセル可能かどうかを判定する。
     */
    public boolean isCancellable() {
        return this.status == BookingStatus.PENDING_APPROVAL
                || this.status == BookingStatus.CONFIRMED;
    }

    /**
     * チェックイン可能かどうかを判定する。
     */
    public boolean isCheckInnable() {
        return this.status == BookingStatus.CONFIRMED;
    }

    /**
     * 完了可能かどうかを判定する。
     */
    public boolean isCompletable() {
        return this.status == BookingStatus.CHECKED_IN;
    }
}
