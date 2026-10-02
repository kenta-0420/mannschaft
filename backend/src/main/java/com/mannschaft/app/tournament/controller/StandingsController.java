package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.RankingSummaryResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentStatsResponse;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.tournament.service.RankingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsCalculationService;
import com.mannschaft.app.tournament.service.StandingsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 順位表・ランキング・チーム成績コントローラー。
 * 7 endpoints: standings, matrix, ranking by statKey, rankings list, recalculate, team history, team stats
 *
 * <p>F08.7 順位UI Wave0: 認証系の順位/ランキング/マトリクス参照に F00 共通可視性ガードを挿入。
 * 大会 visibility 6 レベル（PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE / ADMINS_AND_ABOVE /
 * SCOPE_AFFILIATED / PARTICIPANTS_ONLY）を {@link ContentVisibilityChecker} に委譲して判定する。
 * 可視性は常に親 tournament（{@code tId}）で判定し、divId 引数でも tournament で判定する。
 * 不可視は IDOR 防止のため 404（{@link TournamentErrorCode#TOURNAMENT_NOT_FOUND}）に統一
 * （公開系 {@code verifyPublicAccess} の流儀と整合）。</p>
 *
 * <p>F08.7 順位UI Wave0 検分フォロー: 書込系 {@code recalculate} は読取権限だけで起動できる非対称を是正し、
 * 主催組織 ADMIN/DEPUTY_ADMIN 限定（{@code @accessGuard.isScopeAdmin(...,'ORGANIZATION')}）にした。
 * チーム横断集計 {@code getTeamHistory}/{@code getTeamStats}（大会単位 tId を持たない）は、
 * {@code StandingsQueryService} 側で per-tournament 可視性フィルタを掛けて非公開大会の成績漏洩を防ぐ（B-2b）。</p>
 */
@RestController
@Tag(name = "順位表・ランキング", description = "F08.7 順位表・ランキング参照")
@RequiredArgsConstructor
public class StandingsController {

    private final StandingsQueryService standingsQueryService;
    private final StandingsCalculationService standingsCalculationService;
    private final RankingsCalculationService rankingsCalculationService;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final TeamService teamService;

    /**
     * 大会 visibility ガード。認証ユーザー（未認証なら null）が当該 tournament を閲覧できるか
     * F00 共通可視性 Resolver で判定し、不可視なら 404 を投げる。
     *
     * @param tournamentId 大会 ID（可視性は常にこの親 tournament で判定する）
     */
    private void verifyTournamentVisible(Long tournamentId) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        if (!contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, tournamentId, viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings")
    @Operation(summary = "順位表")
    public ResponseEntity<ApiResponse<List<StandingResponse>>> getStandings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        verifyTournamentVisible(tId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matrix")
    @Operation(summary = "対戦マトリクス")
    public ResponseEntity<ApiResponse<MatrixResponse>> getMatrix(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        verifyTournamentVisible(tId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getMatrix(divId)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/rankings/{statKey}")
    @Operation(summary = "個人ランキング")
    public ResponseEntity<PagedResponse<IndividualRankingResponse>> getRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        verifyTournamentVisible(tId);
        // F08.7 項目①: 閲覧者 ID を伝播し、ランキング選手名を F19.1 本人可視性経由で解決する。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        Page<IndividualRankingResponse> result =
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(page, size), viewerUserId);
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/rankings")
    @Operation(summary = "全ランキング一覧")
    public ResponseEntity<ApiResponse<RankingSummaryResponse>> getRankingSummary(
            @PathVariable Long orgId, @PathVariable Long tId) {
        verifyTournamentVisible(tId);
        // F08.7 項目①: 閲覧者 ID を伝播し、リーダーの選手名を F19.1 本人可視性経由で解決する。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(
                rankingsCalculationService.getRankingSummary(tId, viewerUserId)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings/recalculate")
    @Operation(summary = "順位表の手動再計算")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<Void> recalculate(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        // 書込（再計算）系のため主催組織 ADMIN/DEPUTY_ADMIN 限定（冪等だが読取権限だけで起動できる非対称を是正）。
        // 加えて可視性ガードも維持（不可視大会への 404 を素通しさせない）。
        verifyTournamentVisible(tId);
        standingsCalculationService.recalculate(divId, tId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/teams/{teamId}/tournament-history")
    @Operation(summary = "チームの大会参加履歴")
    public ResponseEntity<ApiResponse<TeamTournamentHistoryResponse>> getTeamHistory(
            @PathVariable String teamId) {
        // slug（URL識別子）を内部 BIGINT に解決してからサービスへ渡す（survey resolveScopeId 流儀）。
        // FE ダッシュボードは slug を渡すため Long のままだと型変換で 400 になりウィジェットが空表示になる。
        Long resolvedTeamId = teamService.resolveTeamId(teamId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getTeamHistory(resolvedTeamId)));
    }

    @GetMapping("/api/v1/teams/{teamId}/tournament-stats")
    @Operation(summary = "チーム通算成績")
    public ResponseEntity<ApiResponse<TeamTournamentStatsResponse>> getTeamStats(
            @PathVariable String teamId) {
        // slug（URL識別子）を内部 BIGINT に解決してからサービスへ渡す（survey resolveScopeId 流儀）。
        Long resolvedTeamId = teamService.resolveTeamId(teamId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getTeamStats(resolvedTeamId)));
    }
}
