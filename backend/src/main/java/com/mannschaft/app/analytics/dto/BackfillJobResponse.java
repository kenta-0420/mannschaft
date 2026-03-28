package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * バックフィルジョブレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class BackfillJobResponse {

    private final String jobId;
    private final String status;
    private final LocalDate from;
    private final LocalDate to;
    private final List<String> targets;
    private final LocalDateTime startedAt;
}
