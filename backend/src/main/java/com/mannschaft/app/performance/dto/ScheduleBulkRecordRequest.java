package com.mannschaft.app.performance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * スケジュールからの一括記録入力リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScheduleBulkRecordRequest {

    @Size(max = 20)
    private final String template;

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

        private final Long userId;
        private final Long metricId;
        private final BigDecimal value;
    }
}
