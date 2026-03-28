package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.queue.QueueScopeType;
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

/**
 * 順番待ち日次統計エンティティ。カウンター単位の日次統計データを管理する。
 */
@Entity
@Table(name = "queue_daily_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QueueDailyStatsEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long counterId;

    @Column(nullable = false)
    private LocalDate statDate;

    @Column(nullable = false)
    @Builder.Default
    private Short totalTickets = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short completedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short cancelledCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short noShowCount = 0;

    @Column(precision = 5, scale = 1)
    private BigDecimal avgWaitMinutes;

    @Column(precision = 5, scale = 1)
    private BigDecimal avgServiceMinutes;

    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Short peakHour;

    @Column(nullable = false)
    @Builder.Default
    private Short qrCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short onlineCount = 0;
}
