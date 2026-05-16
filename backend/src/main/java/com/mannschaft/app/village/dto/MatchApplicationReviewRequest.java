package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 練習試合募集への応募審査リクエスト（F17.1 Phase 2 U6）。
 *
 * <p>{@code status} は ACCEPTED / REJECTED のみ許容。それ以外を指定した場合は
 * Service 層で VILLAGE_068 (MATCH_APPLICATION_INVALID_STATUS) を投げる。</p>
 */
public record MatchApplicationReviewRequest(
        @NotNull VillageMatchApplicationStatus status,
        String reviewComment
) {
}
