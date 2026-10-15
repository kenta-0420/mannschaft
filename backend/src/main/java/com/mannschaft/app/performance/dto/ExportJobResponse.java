package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * CSVエクスポートの非同期ジョブレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ExportJobResponse {

    private final String jobId;
    private final String status;
    private final String message;
}
