package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AbsenceReason;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 保護者連絡の詳細レスポンス。 */
@Getter
@Builder
public class FamilyAttendanceNoticeResponse {

    private Long id;
    private Long teamId;
    private Long studentUserId;
    private Long submitterUserId;
    private LocalDate attendanceDate;
    private FamilyNoticeType noticeType;
    private AbsenceReason reason;
    /** 復号済みの平文。 */
    private String reasonDetail;
    private LocalTime expectedArrivalTime;
    private LocalTime expectedLeaveTime;
    /** Pre-signed ダウンロード URL 一覧。 */
    private List<String> attachedDownloadUrls;
    /** deriveStatus() の結果: PENDING / ACKNOWLEDGED / APPLIED。 */
    private String status;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private boolean appliedToRecord;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static FamilyAttendanceNoticeResponse from(
            FamilyAttendanceNoticeEntity entity,
            List<String> downloadUrls) {
        return FamilyAttendanceNoticeResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .submitterUserId(entity.getSubmitterUserId())
                .attendanceDate(entity.getAttendanceDate())
                .noticeType(entity.getNoticeType())
                .reason(entity.getReason())
                .reasonDetail(entity.getReasonDetail())
                .expectedArrivalTime(entity.getExpectedArrivalTime())
                .expectedLeaveTime(entity.getExpectedLeaveTime())
                .attachedDownloadUrls(downloadUrls)
                .status(entity.deriveStatus().name())
                .acknowledgedBy(entity.getAcknowledgedBy())
                .acknowledgedAt(entity.getAcknowledgedAt())
                .appliedToRecord(Boolean.TRUE.equals(entity.getAppliedToRecord()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
