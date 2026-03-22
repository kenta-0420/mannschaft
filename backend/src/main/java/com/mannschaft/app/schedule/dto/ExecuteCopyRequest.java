package com.mannschaft.app.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 年間行事コピー実行リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ExecuteCopyRequest {

    @NotNull
    private final Integer sourceYear;

    @NotNull
    private final Integer targetYear;

    /** 日付シフトモード: "SAME_WEEKDAY" または "EXACT_DAYS" */
    private final String dateShiftMode;

    @NotEmpty
    @Valid
    private final List<CopyItem> items;

    /**
     * コピーアイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CopyItem {

        @NotNull
        private final Long sourceScheduleId;

        private final LocalDateTime targetStartAt;

        private final LocalDateTime targetEndAt;

        @NotNull
        private final Boolean include;
    }
}
