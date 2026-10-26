package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * ZIP ダウンロードジョブレスポンスDTO。
 */
@Getter
@Builder
public class DownloadZipResponse {
    private final String jobId;
    private final String status;
    private final Integer estimatedCount;
    private final String downloadUrl;
}
