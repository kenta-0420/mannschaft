package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.MatchResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.service.MatchService;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import com.mannschaft.app.tournament.service.TournamentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公開API（SSR・未認証アクセス用）コントローラー。
 * 6 endpoints: list, detail, standings, rankings, bracket, matrix
 */
@RestController
@RequestMapping("/api/v1/public/organizations/{orgId}/tournaments")
@Tag(name = "公開大会API", description = "F08.7 公開大会参照（認証不要）")
@RequiredArgsConstructor
public class PublicTournamentController {

    private final TournamentService tournamentService;
    private final StandingsQueryService standingsQueryService;
    private final RankingsCalculationService rankingsCalculationService;
    private final MatchService matchService;

    @GetMapping
    @Operation(summary = "公開大会一覧")
    public ResponseEntity<PagedResponse<TournamentResponse>> listPublicTournaments(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TournamentResponse> result = tournamentService.listPublicTournaments(orgId, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @GetMapping("/{tId}")
    @Operation(summary = "公開大会詳細")
    public ResponseEntity<ApiResponse<TournamentResponse>> getPublicTournament(
            @PathVariable Long orgId, @PathVariable Long tId) {
        return ResponseEntity.ok(ApiResponse.of(tournamentService.getPublicTournament(orgId, tId)));
    }

    @GetMapping("/{tId}/divisions/{divId}/standings")
    @Operation(summary = "公開順位表")
    public ResponseEntity<ApiResponse<List<StandingResponse>>> getPublicStandings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/{tId}/rankings/{statKey}")
    @Operation(summary = "公開個人ランキング")
    public ResponseEntity<PagedResponse<IndividualRankingResponse>> getPublicRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        tournamentService.verifyPublicAccess(orgId, tId);
        Page<IndividualRankingResponse> result =
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @GetMapping("/{tId}/bracket")
    @Operation(summary = "公開トーナメント表")
    public ResponseEntity<ApiResponse<List<MatchResponse>>> getPublicBracket(
            @PathVariable Long orgId, @PathVariable Long tId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        // TODO: ブラケット専用レスポンス構築
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }

    @GetMapping("/{tId}/divisions/{divId}/matrix")
    @Operation(summary = "公開対戦マトリクス")
    public ResponseEntity<ApiResponse<MatrixResponse>> getPublicMatrix(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getMatrix(divId)));
    }
}
