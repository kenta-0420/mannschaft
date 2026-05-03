package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 生徒出席集計レスポンス DTO。 */
@Getter
@Builder
public class StudentSummaryResponse {

    private Long id;
    private Long teamId;
    private Long studentUserId;
    private Long termId;
    private Short academicYear;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private Short totalSchoolDays;
    private Short presentDays;
    private Short absentDays;
    private Short lateCount;
    private Short earlyLeaveCount;
    private Short officialAbsenceDays;
    private Short schoolActivityDays;
    private Short sickBayDays;
    private Short separateRoomDays;
    private Short onlineDays;
    private Short homeLearningDays;
    private BigDecimal attendanceRate;
    private Short totalPeriods;
    private Short presentPeriods;
    private BigDecimal periodAttendanceRate;
    private String subjectBreakdown;
    private LocalDateTime lastRecalculatedAt;

    /**
     * エンティティからレスポンス DTO を生成する。
     *
     * @param e 出席集計エンティティ
     * @return レスポンス DTO
     */
    public static StudentSummaryResponse from(StudentAttendanceSummaryEntity e) {
        return StudentSummaryResponse.builder()
                .id(e.getId())
                .teamId(e.getTeamId())
                .studentUserId(e.getStudentUserId())
                .termId(e.getTermId())
                .academicYear(e.getAcademicYear())
                .periodFrom(e.getPeriodFrom())
                .periodTo(e.getPeriodTo())
                .totalSchoolDays(e.getTotalSchoolDays())
                .presentDays(e.getPresentDays())
                .absentDays(e.getAbsentDays())
                .lateCount(e.getLateCount())
                .earlyLeaveCount(e.getEarlyLeaveCount())
                .officialAbsenceDays(e.getOfficialAbsenceDays())
                .schoolActivityDays(e.getSchoolActivityDays())
                .sickBayDays(e.getSickBayDays())
                .separateRoomDays(e.getSeparateRoomDays())
                .onlineDays(e.getOnlineDays())
                .homeLearningDays(e.getHomeLearningDays())
                .attendanceRate(e.getAttendanceRate())
                .totalPeriods(e.getTotalPeriods())
                .presentPeriods(e.getPresentPeriods())
                .periodAttendanceRate(e.getPeriodAttendanceRate())
                .subjectBreakdown(e.getSubjectBreakdown())
                .lastRecalculatedAt(e.getLastRecalculatedAt())
                .build();
    }
}
