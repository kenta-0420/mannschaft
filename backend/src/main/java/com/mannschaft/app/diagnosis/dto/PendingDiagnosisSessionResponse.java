package com.mannschaft.app.diagnosis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** 未完了が無い場合もdataを省略せずnullで返す本人検索応答。 */
public record PendingDiagnosisSessionResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) DiagnosisSessionResponse data) { }
