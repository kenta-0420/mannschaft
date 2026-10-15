package com.mannschaft.app.school.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 学級担任設定作成リクエスト DTO。 */
@Getter
@NoArgsConstructor
public class ClassHomeroomCreateRequest {

    /** 学級担任の user_id */
    @NotNull
    private Long homeroomTeacherUserId;

    /** 副担任の user_id 配列（最大3名） */
    private List<Long> assistantTeacherUserIds;

    /** 年度（例: 2026） */
    @NotNull
    @Min(2000)
    @Max(2100)
    private Integer academicYear;

    /** 有効開始日 */
    @NotNull
    private LocalDate effectiveFrom;

    /** 有効終了日（指定しない場合は NULL=現役） */
    private LocalDate effectiveUntil;
}
