package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.service.FixtureService;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import com.mannschaft.app.tournament.service.TournamentService;
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
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: {@code getEmbedStandings} は従来 tId/divId の可視性・束縛検証が
 * 完全欠落しており、非公開大会の順位表を匿名でも閲覧できる IDOR の穴だった。
 * {@link TournamentService#verifyPublicAccess} で tId の公開可視性を、
 * {@link TournamentService#verifyDivisionInTournament} で divId→tId の束縛を検証する。</p>
 */
@RestController
@RequestMapping("/api/v1/embed/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "埋め込みウィジェット", description = "F08.7 埋め込み用順位表・ブラケット・ランキング")
@RequiredArgsConstructor
public class EmbedController {

    private final StandingsQueryService standingsQueryService;
    private final RankingsCalculationService rankingsCalculationService;
    private final FixtureService matchService;
    private final TournamentService tournamentService;

    @GetMapping("/standings/{divId}")
    @Operation(summary = "埋め込み用順位表")
    public ResponseEntity<ApiResponse<List<StandingResponse>>> getEmbedStandings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        tournamentService.verifyDivisionInTournament(tId, divId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/bracket")
    @Operation(summary = "埋め込み用トーナメント表")
    public ResponseEntity<ApiResponse<List<FixtureResponse>>> getEmbedBracket(
            @PathVariable Long orgId, @PathVariable Long tId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        List<FixtureResponse> bracket = matchService.getBracket(tId);
        return ResponseEntity.ok(ApiResponse.of(bracket));
    }

    @GetMapping("/rankings/{statKey}")
    @Operation(summary = "埋め込み用個人ランキング")
    public ResponseEntity<ApiResponse<List<IndividualRankingResponse>>> getEmbedRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey) {
        tournamentService.verifyPublicAccess(orgId, tId);
        // F08.7 項目①: 埋め込みは多くが未ログイン閲覧。閲覧者 ID を伝播し F19.1 本人可視性で名前解決する
        // （未ログインなら汎用ラベル、ログイン中メンバーなら相応の表示名）。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(0, 50), viewerUserId)
                        .getContent()));
    }
}
