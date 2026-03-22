package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 区画割り当てエンティティ。割り当て・解除の履歴を管理する。
 */
@Entity
@Table(name = "parking_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingAssignmentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    private Long vehicleId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long assignedBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime assignedAt = LocalDateTime.now();

    private LocalDate contractStartDate;

    private LocalDate contractEndDate;

    private LocalDateTime releasedAt;

    private Long releasedBy;

    @Column(length = 200)
    private String releaseReason;

    /**
     * 割り当てを解除する。
     */
    public void release(Long releasedBy, String releaseReason) {
        this.releasedAt = LocalDateTime.now();
        this.releasedBy = releasedBy;
        this.releaseReason = releaseReason;
    }

    /**
     * 解除済みかどうかを判定する。
     */
    public boolean isReleased() {
        return this.releasedAt != null;
    }
}
