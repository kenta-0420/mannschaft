package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * 村参加申請の審査リクエスト（F17.1 Phase 1 B6）。
 *
 * <p>承認時は任意、拒否時は Service 層で必須チェック。</p>
 */
public record JoinRequestReviewRequest(
        @Size(max = 500)
        String reviewComment
) {
}
