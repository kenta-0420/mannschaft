package com.mannschaft.app.errorreport.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * エラーレポート一括ステータス更新リクエスト。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportBulkUpdateRequest {

    @NotEmpty
    @Size(max = 100)
    private List<Long> ids;

    @NotNull
    private String status;
}
