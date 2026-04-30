package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 時限出欠1件レスポンス DTO。
 * PeriodAttendanceRecordEntity の情報をクライアントへ返す際に使用する。
 */
@Getter
@Builder
public class PeriodAttendanceResponse {

    private Long id;
    private Long teamId;
    private Long studentUserId;
    private LocalDate attendanceDate;
    private int periodNumber;
    private String subjectName;
    private String teacherName;
    private Long teacherUserId;
    private AttendanceStatus status;
    private Integer lateMinutes;
    private String comment;
    private Long recordedBy;
    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * PeriodAttendanceRecordEntity から PeriodAttendanceResponse を生成するファクトリメソッド。
     */
    public static PeriodAttendanceResponse from(PeriodAttendanceRecordEntity entity) {
        return PeriodAttendanceResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .attendanceDate(entity.getAttendanceDate())
                .periodNumber(entity.getPeriodNumber())
                .subjectName(entity.getSubjectName())
                .teacherName(entity.getTeacherName())
                .teacherUserId(entity.getTeacherUserId())
                .status(entity.getStatus())
                .lateMinutes(entity.getLateMinutes())
                .comment(entity.getComment())
                .recordedBy(entity.getRecordedBy())
                .recordedAt(entity.getRecordedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
