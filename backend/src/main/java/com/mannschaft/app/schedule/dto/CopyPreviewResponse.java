package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 年間行事コピープレビューレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CopyPreviewResponse {

    private final Integer sourceYear;

    private final Integer targetYear;

    private final String dateShiftMode;

    private final List<CopyPreviewItem> items;

    private final Integer totalCopyable;

    private final Integer totalWithConflicts;

    /**
     * プレビューアイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CopyPreviewItem {

        private final Long sourceScheduleId;

        private final String title;

        private final LocalDateTime sourceStartAt;

        private final LocalDateTime sourceEndAt;

        private final LocalDateTime suggestedStartAt;

        private final LocalDateTime suggestedEndAt;

        private final String dateShiftNote;

        private final EventCategoryResponse eventCategory;

        private final Boolean allDay;

        private final CopyConflict conflict;
    }

    /**
     * コピー重複情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CopyConflict {

        private final String type;

        private final Long existingScheduleId;

        private final String existingTitle;
    }
}
