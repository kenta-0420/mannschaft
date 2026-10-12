package com.mannschaft.app.school.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 朝の点呼一括登録リクエスト。 */
@Getter
@NoArgsConstructor
public class DailyRollCallRequest {

    /** 対象日。 */
    @NotNull
    private LocalDate attendanceDate;

    /** 点呼エントリ一覧（1件以上必須）（最大200件。同じ生徒の重複は 400）。 */
    @NotNull
    @NotEmpty
    @Size(max = 200)
    @Valid
    private List<DailyRollCallEntry> entries;
}
