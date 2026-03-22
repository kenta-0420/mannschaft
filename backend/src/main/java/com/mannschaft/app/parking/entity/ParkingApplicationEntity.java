package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.ApplicationSourceType;
import com.mannschaft.app.parking.ParkingApplicationStatus;
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

import java.time.LocalDateTime;

/**
 * 区画申請エンティティ。
 */
@Entity
@Table(name = "parking_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingApplicationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApplicationSourceType sourceType = ApplicationSourceType.VACANCY;

    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ParkingApplicationStatus status = ParkingApplicationStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(length = 500)
    private String message;

    @Column(length = 500)
    private String rejectionReason;

    private Integer lotteryNumber;

    private LocalDateTime decidedAt;

    /**
     * 申請を承認する。
     */
    public void approve() {
        this.status = ParkingApplicationStatus.APPROVED;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * 申請を拒否する。
     */
    public void reject(String rejectionReason) {
        this.status = ParkingApplicationStatus.REJECTED;
        this.rejectionReason = rejectionReason;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * 申請をキャンセルする。
     */
    public void cancel() {
        this.status = ParkingApplicationStatus.CANCELLED;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * 抽選待ちステータスに変更する。
     */
    public void markLotteryPending(int lotteryNumber) {
        this.status = ParkingApplicationStatus.LOTTERY_PENDING;
        this.lotteryNumber = lotteryNumber;
    }
}
