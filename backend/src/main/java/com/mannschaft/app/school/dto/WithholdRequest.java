package com.mannschaft.app.school.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 出席要件評価結果の非開示リクエストDTO（F03.13 Phase 15）。 */
public record WithholdRequest(

        /** 非開示理由（必須） */
        @NotBlank
        @Size(max = 500)
        String withholdReason
) {
}
