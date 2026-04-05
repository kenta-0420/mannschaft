package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.RankingSummaryResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 順位表・ランキング・チーム成績コントローラー。
 * 7 endpoints: standings, matrix, ranking by statKey, rankings list, recalculate, team history, team stats
 */
@RestController
@Tag(name = "順位表・ランキング", description = "F08.7 順位表・ランキング参照")
@RequiredArgsConstructor
public class StandingsController {

    private final StandingsQueryService standingsQueryService;
    private final StandingsCalculationService standingsCalculationService;
    private final RankingsCalculationService rankingsCalculationService;

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings")
    @Operation(summary = "順位表")
    public ResponseEntity<ApiResponse<List<StandingResponse>>> getStandings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matrix")
    @Operation(summary = "対戦マトリクス")
    public ResponseEntity<ApiResponse<MatrixResponse>> getMatrix(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getMatrix(divId)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/rankings/{statKey}")
    @Operation(summary = "個人ランキング")
    public ResponseEntity<PagedResponse<IndividualRankingResponse>> getRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<IndividualRankingResponse> result =
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/rankings")
    @Operation(summary = "全ランキング一覧")
    public ResponseEntity<ApiResponse<RankingSummaryResponse>> getRankingSummary(
            @PathVariable Long orgId, @PathVariable Long tId) {
        return ResponseEntity.ok(ApiResponse.of(rankingsCalculationService.getRankingSummary(tId)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings/recalculate")
    @Operation(summary = "順位表の手動再計算")
    public ResponseEntity<Void> recalculate(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        standingsCalculationService.recalculate(divId, tId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/teams/{teamId}/tournament-history")
    @Operation(summary = "チームの大会参加履歴")
    public ResponseEntity<ApiResponse<TeamTournamentHistoryResponse>> getTeamHistory(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getTeamHistory(teamId)));
    }

    @GetMapping("/api/v1/teams/{teamId}/tournament-stats")
    @Operation(summary = "チーム通算成績")
    public ResponseEntity<ApiResponse<TeamTournamentStatsResponse>> getTeamStats(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getTeamStats(teamId)));
    }
}
