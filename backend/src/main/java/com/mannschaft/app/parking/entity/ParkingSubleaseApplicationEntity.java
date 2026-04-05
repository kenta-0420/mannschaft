package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.SubleaseApplicationStatus;
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
 * サブリース申請エンティティ。
 */
@Entity
@Table(name = "parking_sublease_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSubleaseApplicationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long subleaseId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long vehicleId;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubleaseApplicationStatus status = SubleaseApplicationStatus.PENDING;

    private LocalDateTime decidedAt;

    /**
     * 承認する。
     */
    public void approve() {
        this.status = SubleaseApplicationStatus.APPROVED;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * 拒否する。
     */
    public void reject() {
        this.status = SubleaseApplicationStatus.REJECTED;
        this.decidedAt = LocalDateTime.now();
    }

    /**
     * キャンセルする。
     */
    public void cancel() {
        this.status = SubleaseApplicationStatus.CANCELLED;
        this.decidedAt = LocalDateTime.now();
    }
}
