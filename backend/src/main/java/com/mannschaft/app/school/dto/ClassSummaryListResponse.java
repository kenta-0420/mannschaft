package com.mannschaft.app.school.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** クラス全員の出席集計一覧レスポンス DTO。 */
@Getter
@Builder
public class ClassSummaryListResponse {

    private Long teamId;
    private Short academicYear;
    private Long termId;
    private int total;
    private List<StudentSummaryResponse> summaries;
}
