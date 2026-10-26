package com.mannschaft.app.errorreport.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * エラーレポートステータス・重要度更新リクエスト（部分更新）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportUpdateRequest {

    private String status;

    private String severity;

    @Size(max = 2000)
    private String adminNote;
}
