package com.mannschaft.app.jobmatching.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 完了差し戻しリクエスト。Requester が差し戻し理由を明示する必要があるため required。
 */
public record RejectCompletionRequest(
        @NotBlank @Size(max = 1000) String reason
) {
}
