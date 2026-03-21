package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.queue.AcceptMode;
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

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 順番待ちカウンターエンティティ。実際のサービス提供窓口を管理する。
 */
@Entity
@Table(name = "queue_counters")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QueueCounterEntity extends BaseEntity {

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AcceptMode acceptMode = AcceptMode.BOTH;

    @Column(nullable = false)
    @Builder.Default
    private Short avgServiceMinutes = 10;

    @Column(nullable = false)
    @Builder.Default
    private Boolean avgServiceMinutesManual = false;

    @Column(nullable = false)
    @Builder.Default
    private Short maxQueueSize = 50;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAccepting = true;

    private LocalTime operatingTimeFrom;

    private LocalTime operatingTimeTo;

    @Column(nullable = false)
    @Builder.Default
    private Short displayOrder = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * カウンター情報を更新する。
     */
    public void update(String name, String description, AcceptMode acceptMode,
                       Short avgServiceMinutes, Boolean avgServiceMinutesManual,
                       Short maxQueueSize, Boolean isActive, Boolean isAccepting,
                       LocalTime operatingTimeFrom, LocalTime operatingTimeTo,
                       Short displayOrder) {
        this.name = name;
        this.description = description;
        this.acceptMode = acceptMode;
        this.avgServiceMinutes = avgServiceMinutes;
        this.avgServiceMinutesManual = avgServiceMinutesManual;
        this.maxQueueSize = maxQueueSize;
        this.isActive = isActive;
        this.isAccepting = isAccepting;
        this.operatingTimeFrom = operatingTimeFrom;
        this.operatingTimeTo = operatingTimeTo;
        this.displayOrder = displayOrder;
    }

    /**
     * 受付状態を切り替える。
     *
     * @param accepting 受付可否
     */
    public void toggleAccepting(boolean accepting) {
        this.isAccepting = accepting;
    }
}
