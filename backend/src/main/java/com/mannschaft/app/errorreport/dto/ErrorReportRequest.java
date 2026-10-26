package com.mannschaft.app.errorreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * フロントエンドエラーレポート送信リクエスト。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorReportRequest {

    @NotBlank
    @Size(max = 1000)
    private String errorMessage;

    private String stackTrace;

    @NotBlank
    @Size(max = 2048)
    private String pageUrl;

    @Size(max = 500)
    private String userAgent;

    @Size(max = 1000)
    private String userComment;

    private Long userId;

    @NotNull
    private LocalDateTime occurredAt;

    @Size(max = 36)
    private String requestId;
}
