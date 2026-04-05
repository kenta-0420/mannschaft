package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.schedule.CrossRefStatus;
import com.mannschaft.app.schedule.CrossRefTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュールクロスリファレンスエンティティ。チーム・組織間のスケジュール招待を管理する。
 */
@Entity
@Table(name = "schedule_cross_refs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleCrossRefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sourceScheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CrossRefTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    private Long targetScheduleId;

    private Long invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CrossRefStatus status;

    @Column(length = 500)
    private String message;

    private LocalDateTime respondedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 招待を承認する。
     *
     * @param targetScheduleId 招待先で作成されたスケジュールID
     */
    public void accept(Long targetScheduleId) {
        this.status = CrossRefStatus.ACCEPTED;
        this.respondedAt = LocalDateTime.now();
        this.targetScheduleId = targetScheduleId;
    }

    /**
     * 招待を拒否する。
     */
    public void reject() {
        this.status = CrossRefStatus.REJECTED;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 招待をキャンセルする。
     */
    public void cancel() {
        this.status = CrossRefStatus.CANCELLED;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 確認待ち状態にする。
     */
    public void awaitConfirmation() {
        this.status = CrossRefStatus.AWAITING_CONFIRMATION;
    }
}
