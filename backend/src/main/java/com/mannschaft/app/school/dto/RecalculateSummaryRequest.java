package com.mannschaft.app.school.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 出席集計再計算リクエスト DTO。 */
@Getter
@NoArgsConstructor
public class RecalculateSummaryRequest {

    @NotNull
    private Long teamId;

    @NotNull
    private Short academicYear;

    private Long termId;

    /** 集計期間開始日（YYYY-MM-DD 形式）。 */
    @NotNull
    private String periodFrom;

    /** 集計期間終了日（YYYY-MM-DD 形式）。 */
    @NotNull
    private String periodTo;
}
