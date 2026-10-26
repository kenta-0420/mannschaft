package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 昇降格プレビューレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PromotionPreviewResponse {

    private final List<PromotionCandidate> candidates;

    @Getter
    @RequiredArgsConstructor
    public static class PromotionCandidate {
        private final Long teamId;
        private final Long fromDivisionId;
        private final String fromDivisionName;
        private final Long toDivisionId;
        private final String toDivisionName;
        private final String type;
        private final Integer finalRank;
    }
}
