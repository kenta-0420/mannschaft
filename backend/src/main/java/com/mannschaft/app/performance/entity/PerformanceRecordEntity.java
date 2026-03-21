package com.mannschaft.app.performance.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.performance.RecordSource;
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
 * パフォーマンス記録エンティティ。メンバーごとの指標値の記録を管理する。
 */
@Entity
@Table(name = "performance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PerformanceRecordEntity extends BaseEntity {

    @Column(nullable = false)
    private Long metricId;

    @Column(nullable = false)
    private Long userId;

    private Long scheduleId;

    private Long activityResultId;

    @Column(nullable = false)
    private LocalDate recordedDate;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal value;

    @Column(length = 300)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private RecordSource source = RecordSource.ADMIN;

    private Long recordedBy;

    /**
     * 記録を更新する。ソースをADMINに変更し活動記録との自動同期を解除する。
     */
    public void update(BigDecimal value, String note, LocalDate recordedDate) {
        this.value = value;
        this.note = note;
        this.recordedDate = recordedDate;
        this.source = RecordSource.ADMIN;
    }
}
