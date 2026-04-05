package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * バッチジョブログレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BatchJobLogResponse {

    private final Long id;
    private final String jobName;
    private final String status;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;
    private final Integer processedCount;
    private final String errorMessage;
    private final LocalDateTime createdAt;
}
