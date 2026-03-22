package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 時限テンプレートエンティティ。組織単位で時限の開始・終了時刻を定義する。
 */
@Entity
@Table(name = "timetable_period_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetablePeriodTemplateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Integer periodNumber;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isBreak = false;
}
