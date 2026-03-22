package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.ListingStatus;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 譲渡希望エンティティ。
 */
@Entity
@Table(name = "parking_listings")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingListingEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    @Column(nullable = false)
    private Long assignmentId;

    @Column(nullable = false)
    private Long listedBy;

    @Column(length = 500)
    private String reason;

    private LocalDate desiredTransferDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ListingStatus status = ListingStatus.OPEN;

    private Long transfereeUserId;

    private Long transfereeVehicleId;

    private LocalDateTime transferredAt;

    private LocalDateTime deletedAt;

    /**
     * 譲渡希望を更新する。
     */
    public void update(String reason, LocalDate desiredTransferDate) {
        this.reason = reason;
        this.desiredTransferDate = desiredTransferDate;
    }

    /**
     * 譲渡先を予約する。
     */
    public void reserve(Long transfereeUserId, Long transfereeVehicleId) {
        this.status = ListingStatus.RESERVED;
        this.transfereeUserId = transfereeUserId;
        this.transfereeVehicleId = transfereeVehicleId;
    }

    /**
     * 譲渡を確定する。
     */
    public void transfer() {
        this.status = ListingStatus.TRANSFERRED;
        this.transferredAt = LocalDateTime.now();
    }

    /**
     * 譲渡希望をキャンセルする。
     */
    public void cancel() {
        this.status = ListingStatus.CANCELLED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
