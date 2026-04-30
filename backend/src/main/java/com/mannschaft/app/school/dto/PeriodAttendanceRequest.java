package com.mannschaft.app.school.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 時限出欠一括登録リクエスト DTO。
 * 教科担任が特定日・特定時限の出欠を一括登録する際に使用する。
 */
@Getter
@NoArgsConstructor
public class PeriodAttendanceRequest {

    /** 出欠対象日。 */
    @NotNull
    private LocalDate attendanceDate;

    /** 出欠エントリ一覧。1件以上必須。 */
    @NotNull
    @NotEmpty
    @Valid
    private List<PeriodAttendanceEntry> entries;
}
