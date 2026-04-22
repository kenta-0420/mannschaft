package com.mannschaft.app.jobmatching.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 応募不採用リクエスト。不採用理由（任意）を保持する。
 */
public record RejectApplicationRequest(
        @Size(max = 500) String reason
) {
}
