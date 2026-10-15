package com.mannschaft.app.errorreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * F12.5 Phase 2 — エラーレポートへのコメント追加リクエスト。
 */
@Getter
@Setter
public class ErrorReportCommentRequest {

    @NotBlank
    @Size(max = 2000)
    private String content;
}
