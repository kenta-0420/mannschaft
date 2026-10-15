package com.mannschaft.app.performance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 指標並び順一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SortOrderRequest {

    @NotEmpty
    @Valid
    private final List<SortOrderEntry> orders;

    /**
     * 並び順エントリー。
     */
    @Getter
    @RequiredArgsConstructor
    public static class SortOrderEntry {

        @NotNull
        private final Long id;

        @NotNull
        private final Integer sortOrder;
    }
}
