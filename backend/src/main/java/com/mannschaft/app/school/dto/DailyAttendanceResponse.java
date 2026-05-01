package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AbsenceReason;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 日次出欠1件レスポンス。 */
@Getter
@Builder
public class DailyAttendanceResponse {

    private Long id;
    private Long teamId;
    private Long studentUserId;
    private LocalDate attendanceDate;
    private AttendanceStatus status;
    private AbsenceReason absenceReason;
    private LocalTime arrivalTime;
    private LocalTime leaveTime;
    private String comment;
    private Long familyNoticeId;
    private Long recordedBy;
    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * エンティティから DailyAttendanceResponse を生成するファクトリメソッド。
     *
     * @param entity 日次出欠レコードエンティティ
     * @return DailyAttendanceResponse
     */
    public static DailyAttendanceResponse from(DailyAttendanceRecordEntity entity) {
        return DailyAttendanceResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .attendanceDate(entity.getAttendanceDate())
                .status(entity.getStatus())
                .absenceReason(entity.getAbsenceReason())
                .arrivalTime(entity.getArrivalTime())
                .leaveTime(entity.getLeaveTime())
                .comment(entity.getComment())
                .familyNoticeId(entity.getFamilyNoticeId())
                .recordedBy(entity.getRecordedBy())
                .recordedAt(entity.getRecordedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
