package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 非同期エクスポートレスポンス。
 */
@Getter
@Builder
public class ExportResponse {

    private String jobId;
    private String status;
    private String message;
}
