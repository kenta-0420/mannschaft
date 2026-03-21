package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 埋め込みウィジェット用コントローラー。
 * 3 endpoints: standings, bracket, rankings
 */
@RestController
@RequestMapping("/api/v1/embed/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "埋め込みウィジェット", description = "F08.7 埋め込み用順位表・ブラケット・ランキング")
@RequiredArgsConstructor
public class EmbedController {

    private final StandingsQueryService standingsQueryService;
    private final RankingsCalculationService rankingsCalculationService;

    @GetMapping("/standings/{divId}")
    @Operation(summary = "埋め込み用順位表")
    public ResponseEntity<ApiResponse<List<StandingResponse>>> getEmbedStandings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/bracket")
    @Operation(summary = "埋め込み用トーナメント表")
    public ResponseEntity<ApiResponse<List<?>>> getEmbedBracket(
            @PathVariable Long orgId, @PathVariable Long tId) {
        // TODO: ブラケット専用レスポンス
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }

    @GetMapping("/rankings/{statKey}")
    @Operation(summary = "埋め込み用個人ランキング")
    public ResponseEntity<ApiResponse<List<IndividualRankingResponse>>> getEmbedRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey) {
        return ResponseEntity.ok(ApiResponse.of(
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(0, 50)).getContent()));
    }
}
