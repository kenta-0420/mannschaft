package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * CSV取込確定リクエスト。プレビューで返されたキーを指定して確定INSERTする。
 */
public record CsvImportConfirmRequest(

        @NotBlank
        String previewKey,

        @NotNull
        Long fiscalYearId
) {
}
