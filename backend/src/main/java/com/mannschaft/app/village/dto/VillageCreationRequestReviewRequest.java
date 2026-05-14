package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * 村作成申請の審査リクエスト（F17.1 Phase 1 B5）。
 *
 * <p>承認時は任意、拒否時はサービス層で必須チェック。</p>
 */
public record VillageCreationRequestReviewRequest(
        @Size(max = 2000)
        String reviewComment
) {
}
