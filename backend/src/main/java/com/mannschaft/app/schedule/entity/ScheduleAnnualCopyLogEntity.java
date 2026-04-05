package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.DateShiftMode;
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

/**
 * 年間行事コピーログエンティティ。年度間コピーの実行履歴を管理する。
 */
@Entity
@Table(name = "schedule_annual_copy_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleAnnualCopyLogEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false)
    private Integer sourceAcademicYear;

    @Column(nullable = false)
    private Integer targetAcademicYear;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalCopied = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalSkipped = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DateShiftMode dateShiftMode;

    private Long executedBy;
}
