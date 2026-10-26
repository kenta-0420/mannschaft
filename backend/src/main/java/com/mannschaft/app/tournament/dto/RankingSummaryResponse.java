package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 全ランキング一覧（項目別サマリー）レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RankingSummaryResponse {

    private final List<RankingCategory> categories;

    @Getter
    @RequiredArgsConstructor
    public static class RankingCategory {
        private final String statKey;
        private final String name;
        private final String rankingLabel;
        private final String unit;
        private final IndividualRankingResponse leader;
    }
}
