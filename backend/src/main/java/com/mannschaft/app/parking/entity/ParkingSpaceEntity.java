package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.AllocationMethod;
import com.mannschaft.app.parking.ApplicationStatus;
import com.mannschaft.app.parking.SpaceStatus;
import com.mannschaft.app.parking.SpaceType;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 駐車区画マスターエンティティ。
 */
@Entity
@Table(name = "parking_spaces")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSpaceEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 20)
    private String spaceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpaceType spaceType;

    @Column(length = 50)
    private String spaceTypeLabel;

    @Column(precision = 10, scale = 0)
    private BigDecimal pricePerMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SpaceStatus status = SpaceStatus.VACANT;

    @Column(length = 10)
    private String floor;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApplicationStatus applicationStatus = ApplicationStatus.NOT_ACCEPTING;

    @Enumerated(EnumType.STRING)
    private AllocationMethod allocationMethod;

    private LocalDateTime applicationDeadline;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 区画情報を更新する。
     */
    public void update(String spaceNumber, SpaceType spaceType, String spaceTypeLabel,
                       BigDecimal pricePerMonth, String floor, String notes) {
        this.spaceNumber = spaceNumber;
        this.spaceType = spaceType;
        this.spaceTypeLabel = spaceTypeLabel;
        this.pricePerMonth = pricePerMonth;
        this.floor = floor;
        this.notes = notes;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(SpaceStatus status) {
        this.status = status;
    }

    /**
     * 申請受付を開始する。
     */
    public void acceptApplications(AllocationMethod allocationMethod, LocalDateTime applicationDeadline) {
        this.applicationStatus = ApplicationStatus.ACCEPTING;
        this.allocationMethod = allocationMethod;
        this.applicationDeadline = applicationDeadline;
    }

    /**
     * 申請受付を終了する（抽選締切）。
     */
    public void closeLottery() {
        this.applicationStatus = ApplicationStatus.LOTTERY_CLOSED;
    }

    /**
     * 申請受付をリセットする。
     */
    public void resetApplicationStatus() {
        this.applicationStatus = ApplicationStatus.NOT_ACCEPTING;
        this.allocationMethod = null;
        this.applicationDeadline = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
