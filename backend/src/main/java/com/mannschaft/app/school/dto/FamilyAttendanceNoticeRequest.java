package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AbsenceReason;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 保護者からの欠席・遅刻連絡送信リクエスト。 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FamilyAttendanceNoticeRequest {

    @NotNull
    private Long teamId;

    @NotNull
    private Long studentUserId;

    @NotNull
    private LocalDate attendanceDate;

    @NotNull
    private FamilyNoticeType noticeType;

    private AbsenceReason reason;

    @Size(max = 1000)
    private String reasonDetail;

    /** 遅刻連絡時の到着予定時刻（HH:mm）。 */
    private String expectedArrivalTime;

    /** 早退連絡時の早退予定時刻（HH:mm）。 */
    private String expectedLeaveTime;

    /** 既アップロード済みの R2 オブジェクトキー一覧（添付ファイル）。 */
    private List<String> attachedFileKeys;
}
