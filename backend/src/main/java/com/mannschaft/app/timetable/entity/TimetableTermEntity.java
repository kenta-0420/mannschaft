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

import java.time.LocalDate;

/**
 * 学期エンティティ。時間割の適用期間（学期・タームなど）を管理する。
 */
@Entity
@Table(name = "timetable_terms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableTermEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false)
    private Integer academicYear;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer sortOrder;
}
