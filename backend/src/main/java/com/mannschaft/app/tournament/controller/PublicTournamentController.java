package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.service.FixtureService;
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
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers(GET, "/api/v1/public/organizations/&#42;/tournaments"
 * 他 standings / matrix / rankings / bracket).permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F08.7 公開大会参照。<b>visibility=PUBLIC の大会のみ</b>を {@code verifyPublicAccess}
 * が通し、非 PUBLIC は 404 で存在を秘匿する。「PUBLIC＝誰でも閲覧」という仕様上の約束を満たすために未ログイン到達が必須（permitAll
 * を怠ると deny-by-default が 401 で弾き公約違反になる）。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic({
        "/api/v1/public/organizations/*/tournaments",
        "/api/v1/public/organizations/*/tournaments/*",
        "/api/v1/public/organizations/*/tournaments/*/divisions/*/standings",
        "/api/v1/public/organizations/*/tournaments/*/divisions/*/matrix",
        "/api/v1/public/organizations/*/tournaments/*/rankings/*",
        "/api/v1/public/organizations/*/tournaments/*/bracket"
})
@RestController
@RequestMapping("/api/v1/public/organizations/{orgId}/tournaments")
@Tag(name = "公開大会API", description = "F08.7 公開大会参照（認証不要）")
@RequiredArgsConstructor
public class PublicTournamentController {

    private final TournamentService tournamentService;
    private final StandingsQueryService standingsQueryService;
    private final RankingsCalculationService rankingsCalculationService;
    private final FixtureService matchService;

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
        // divId が tId 配下であることを束縛検証する（台帳指摘の穴・BOLA 是正）。
        tournamentService.verifyDivisionInTournament(tId, divId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getStandings(divId)));
    }

    @GetMapping("/{tId}/rankings/{statKey}")
    @Operation(summary = "公開個人ランキング")
    public ResponseEntity<PagedResponse<IndividualRankingResponse>> getPublicRankings(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        tournamentService.verifyPublicAccess(orgId, tId);
        // F08.7 項目①: 公開SSR経路。閲覧者 ID を伝播し F19.1 本人可視性で名前解決する
        // （未ログインなら汎用ラベル、ログイン中メンバーなら相応の表示名）。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        Page<IndividualRankingResponse> result =
                rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(page, size), viewerUserId);
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @GetMapping("/{tId}/bracket")
    @Operation(summary = "公開トーナメント表")
    public ResponseEntity<ApiResponse<List<FixtureResponse>>> getPublicBracket(
            @PathVariable Long orgId, @PathVariable Long tId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        List<FixtureResponse> bracket = matchService.getBracket(tId);
        return ResponseEntity.ok(ApiResponse.of(bracket));
    }

    @GetMapping("/{tId}/divisions/{divId}/matrix")
    @Operation(summary = "公開対戦マトリクス")
    public ResponseEntity<ApiResponse<MatrixResponse>> getPublicMatrix(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        tournamentService.verifyPublicAccess(orgId, tId);
        // divId が tId 配下であることを束縛検証する（台帳指摘の穴・BOLA 是正）。
        tournamentService.verifyDivisionInTournament(tId, divId);
        return ResponseEntity.ok(ApiResponse.of(standingsQueryService.getMatrix(divId)));
    }
}
