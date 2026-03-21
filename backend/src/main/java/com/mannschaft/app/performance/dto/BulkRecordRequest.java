package com.mannschaft.app.performance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一括記録入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkRecordRequest {

    @NotNull
    private final LocalDate recordedDate;

    @Size(max = 300)
    private final String note;

    @NotEmpty
    @Size(max = 200)
    @Valid
    private final List<Entry> entries;

    /**
     * 一括入力のエントリー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class Entry {

        @NotNull
        private final Long userId;

        @NotNull
        private final Long metricId;

        @NotNull
        private final BigDecimal value;
    }
}
