package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.BackfillTarget;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * バックフィルリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class BackfillRequest {

    @NotNull
    private final LocalDate from;

    @NotNull
    private final LocalDate to;

    @NotEmpty
    private final List<BackfillTarget> targets;
}
