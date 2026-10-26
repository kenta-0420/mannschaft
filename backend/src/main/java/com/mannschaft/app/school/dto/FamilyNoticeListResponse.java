package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/** 担任向け当日保護者連絡一覧レスポンス。 */
@Getter
@Builder
public class FamilyNoticeListResponse {

    private Long teamId;
    private LocalDate attendanceDate;
    private List<FamilyAttendanceNoticeResponse> records;
    private int totalCount;
    private int unacknowledgedCount;
}
