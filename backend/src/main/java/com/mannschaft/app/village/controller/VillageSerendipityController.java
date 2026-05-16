package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.VillageSerendipityRankingResponse;
import com.mannschaft.app.village.dto.VillageSerendipityScoreResponse;
import com.mannschaft.app.village.service.VillageSerendipityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコア Controller。
 *
 * <p>自分のスコア取得とランキング取得の 2 つの読み取り API を提供する。
 * 書き込み（スコア加算）は日次バッチ
 * {@link com.mannschaft.app.village.batch.VillageSerendipityBatchService}
 * からのみ行われ、Controller からは触れない。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET /api/v1/villages/{villageId}/serendipity-scores/me} — 自分のスコア</li>
 *   <li>{@code GET /api/v1/villages/{villageId}/serendipity-scores/ranking?limit=N} — ランキング</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/serendipity-scores")
@Tag(name = "村ご縁スコア (F17.1)",
     description = "Phase 3-β: 村人同士の出会い・交流度を可視化するスコアの取得 API")
@RequiredArgsConstructor
public class VillageSerendipityController {

    private final VillageSerendipityService serendipityService;

    /**
     * 自分のご縁スコアを取得する。
     *
     * <p>レコードがまだ存在しない（初回バッチ実行前）の場合は 404 を返す。
     * UI 側で「まだスコアがありません」と案内する想定。</p>
     */
    @GetMapping("/me")
    @Operation(summary = "自分のご縁スコアを取得する")
    public ApiResponse<VillageSerendipityScoreResponse> getMyScore(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(serendipityService.getMyScore(villageId, actorUserId));
    }

    /**
     * ご縁スコアの上位 N 件ランキングを取得する。
     *
     * @param villageId 村 ID
     * @param limit     上位件数（省略時 10、最大 100）
     */
    @GetMapping("/ranking")
    @Operation(summary = "ご縁スコアの上位ランキングを取得する（最大100件）")
    public ApiResponse<VillageSerendipityRankingResponse> getRanking(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return ApiResponse.of(serendipityService.getRanking(villageId, limit));
    }
}
