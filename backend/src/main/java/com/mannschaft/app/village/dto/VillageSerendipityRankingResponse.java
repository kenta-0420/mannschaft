package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * F17.1 Phase 3-β — ご縁スコアランキング応答 DTO。
 *
 * <p>フロント型 {@code VillageSerendipityRankingResponse} と整合させ、
 * 上位 N 件のスコア配列を {@code items} に、当該村の総人数を {@code total} に保持する。</p>
 *
 * @param items 上位 N 件のスコア（{@code rank} を 1 始まりで設定済み）
 * @param total 当該村のスコアレコード総数
 */
public record VillageSerendipityRankingResponse(
        List<VillageSerendipityScoreResponse> items,
        long total
) {
}
