package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.MatrixResponse;
import com.mannschaft.app.tournament.dto.StandingResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
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

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF出力コントローラー。
 * 4 endpoints: standings PDF, bracket PDF, rankings PDF, matrix PDF
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "大会PDF出力", description = "F08.7 順位表・ブラケット・ランキング・マトリクスPDF")
@RequiredArgsConstructor
public class TournamentPdfController {

    private final PdfGeneratorService pdfGeneratorService;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final StandingsQueryService standingsQueryService;
    private final RankingsCalculationService rankingsCalculationService;

    @GetMapping("/divisions/{divId}/standings/pdf")
    @Operation(summary = "順位表PDF")
    public ResponseEntity<byte[]> getStandingsPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        TournamentEntity tournament = findTournamentOrThrow(tId);
        TournamentDivisionEntity division = findDivisionOrThrow(divId, tId);

        List<StandingResponse> standings = standingsQueryService.getStandings(divId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "順位表");
        variables.put("standings", standings);
        variables.put("tournamentName", tournament.getName());
        variables.put("divisionName", division.getName());

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/tournament-standings", variables);

        String fileName = PdfFileNameBuilder.of("順位表")
                .date(resolveDate(tournament))
                .identifier(tournament.getName() + "_" + division.getName())
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }

    @GetMapping("/bracket/pdf")
    @Operation(summary = "トーナメント表PDF")
    public ResponseEntity<byte[]> getBracketPdf(
            @PathVariable Long orgId, @PathVariable Long tId) {
        TournamentEntity tournament = findTournamentOrThrow(tId);

        List<?> rounds = Collections.emptyList();

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "トーナメント表");
        variables.put("rounds", rounds);
        variables.put("tournamentName", tournament.getName());

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/tournament-bracket", variables);

        String fileName = PdfFileNameBuilder.of("トーナメント表")
                .date(resolveDate(tournament))
                .identifier(tournament.getName())
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }

    @GetMapping("/rankings/{statKey}/pdf")
    @Operation(summary = "個人ランキングPDF")
    public ResponseEntity<byte[]> getRankingsPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable String statKey) {
        TournamentEntity tournament = findTournamentOrThrow(tId);

        // 全件取得（PDF用なのでページネーション不要、上限1000件）
        var rankingsPage = rankingsCalculationService.getRankings(tId, statKey, PageRequest.of(0, 1000));
        var rankings = rankingsPage.getContent();

        // statKey のラベルを先頭レコードから取得（なければ statKey をそのまま使用）
        String statKeyLabel = rankings.isEmpty() ? statKey
                : (rankings.get(0).getRankingLabel() != null ? rankings.get(0).getRankingLabel() : statKey);

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "ランキング - " + statKeyLabel);
        variables.put("rankings", rankings);
        variables.put("statKeyLabel", statKeyLabel);
        variables.put("tournamentName", tournament.getName());

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/tournament-rankings", variables);

        String fileName = PdfFileNameBuilder.of("ランキング")
                .date(resolveDate(tournament))
                .identifier(tournament.getName() + "_" + statKey)
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }

    @GetMapping("/divisions/{divId}/matrix/pdf")
    @Operation(summary = "対戦マトリクスPDF")
    public ResponseEntity<byte[]> getMatrixPdf(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        TournamentEntity tournament = findTournamentOrThrow(tId);
        TournamentDivisionEntity division = findDivisionOrThrow(divId, tId);

        MatrixResponse matrix = standingsQueryService.getMatrix(divId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "対戦表");
        variables.put("teams", matrix.getParticipants());
        variables.put("matrix", matrix.getCells());
        variables.put("tournamentName", tournament.getName());
        variables.put("divisionName", division.getName());

        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/tournament-matrix", variables);

        String fileName = PdfFileNameBuilder.of("対戦表")
                .date(resolveDate(tournament))
                .identifier(tournament.getName() + "_" + division.getName())
                .build();

        return PdfResponseHelper.toResponse(pdfBytes, fileName);
    }

    // ===== Private =====

    private TournamentEntity findTournamentOrThrow(Long tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
    }

    private TournamentDivisionEntity findDivisionOrThrow(Long divId, Long tournamentId) {
        return divisionRepository.findByIdAndTournamentId(divId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }

    /**
     * 大会の開始日を返す。未設定の場合は本日を返す。
     */
    private LocalDate resolveDate(TournamentEntity tournament) {
        return tournament.getStartDate() != null ? tournament.getStartDate() : LocalDate.now();
    }
}
