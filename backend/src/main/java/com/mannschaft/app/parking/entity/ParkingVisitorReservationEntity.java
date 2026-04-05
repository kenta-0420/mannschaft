package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.VisitorReservationStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 来場者予約エンティティ。
 */
@Entity
@Table(name = "parking_visitor_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingVisitorReservationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    @Column(nullable = false)
    private Long reservedBy;

    @Column(length = 100)
    private String visitorName;

    @Column(length = 30)
    private String visitorPlateNumber;

    @Column(nullable = false)
    private LocalDate reservedDate;

    @Column(nullable = false)
    private LocalTime timeFrom;

    @Column(nullable = false)
    private LocalTime timeTo;

    @Column(length = 200)
    private String purpose;

    @Column(length = 500)
    private String adminComment;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VisitorReservationStatus status = VisitorReservationStatus.PENDING_APPROVAL;

    /**
     * 予約を承認する。
     */
    public void approve(Long approvedBy) {
        this.status = VisitorReservationStatus.CONFIRMED;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 予約を拒否する。
     */
    public void reject(Long approvedBy, String adminComment) {
        this.status = VisitorReservationStatus.REJECTED;
        this.approvedBy = approvedBy;
        this.adminComment = adminComment;
    }

    /**
     * チェックインする。
     */
    public void checkIn() {
        this.status = VisitorReservationStatus.CHECKED_IN;
    }

    /**
     * 完了にする。
     */
    public void complete() {
        this.status = VisitorReservationStatus.COMPLETED;
    }

    /**
     * キャンセルする。
     */
    public void cancel() {
        this.status = VisitorReservationStatus.CANCELLED;
    }
}
