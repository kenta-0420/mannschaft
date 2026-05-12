package com.mannschaft.app.repairplan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 修繕計画項目 CSV インポート確定リクエスト。
 *
 * <p>preview で発行された {@code import_token} を渡してインポートを確定する。</p>
 */
public record CsvImportConfirmRequest(

        @NotBlank
        @JsonProperty("preview_key")
        String previewKey
) {
}
