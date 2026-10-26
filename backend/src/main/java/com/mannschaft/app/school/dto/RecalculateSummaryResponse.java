package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** 再計算結果レスポンス DTO。 */
@Getter
@Builder
public class RecalculateSummaryResponse {

    private Long studentUserId;
    private Long teamId;
    private Short academicYear;
    private Long termId;
    private LocalDateTime recalculatedAt;
    private StudentSummaryResponse summary;
}
