package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.CreateMatchdayRequest;
import com.mannschaft.app.tournament.dto.CreateRosterRequest;
import com.mannschaft.app.tournament.dto.MatchResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.PlayerStatBatchRequest;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.dto.StatusChangeRequest;
import com.mannschaft.app.tournament.service.MatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 対戦カード・結果・出場メンバー管理コントローラー。
 * 12 endpoints: Matchday 3 (GET list, POST create, POST generate) +
 *               Match 5 (GET detail, PATCH score, PATCH player-stats, PATCH status, POST batch, POST import) +
 *               Roster 3 (GET list, POST create, DELETE)
 *               = technically the POST import is a stub so 12 total
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "対戦カード・結果管理", description = "F08.7 対戦カード・結果・出場メンバーCRUD")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    // ===== Matchday =====

    @GetMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節一覧")
    public ResponseEntity<ApiResponse<List<MatchdayResponse>>> listMatchdays(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.ok(ApiResponse.of(matchService.listMatchdays(divId)));
    }

    @PostMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節作成")
    public ResponseEntity<ApiResponse<MatchdayResponse>> createMatchday(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody CreateMatchdayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createMatchday(divId, request)));
    }

    @PostMapping("/divisions/{divId}/matchdays/generate")
    @Operation(summary = "対戦カード自動生成")
    public ResponseEntity<ApiResponse<List<MatchdayResponse>>> generateMatchdays(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.generateMatchdays(tId, divId)));
    }

    // ===== Match =====

    @GetMapping("/matches/{matchId}")
    @Operation(summary = "試合詳細")
    public ResponseEntity<ApiResponse<MatchResponse>> getMatch(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId) {
        return ResponseEntity.ok(ApiResponse.of(matchService.getMatch(matchId)));
    }

    @PatchMapping("/matches/{matchId}/score")
    @Operation(summary = "スコア入力・更新")
    public ResponseEntity<ApiResponse<MatchResponse>> updateScore(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody ScoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(matchService.updateScore(tId, matchId, request)));
    }

    @PatchMapping("/matches/{matchId}/player-stats")
    @Operation(summary = "個人成績一括入力")
    public ResponseEntity<ApiResponse<MatchResponse>> updatePlayerStats(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody PlayerStatBatchRequest request) {
        return ResponseEntity.ok(ApiResponse.of(matchService.updatePlayerStats(tId, matchId, request)));
    }

    @PatchMapping("/matches/{matchId}/status")
    @Operation(summary = "試合ステータス変更")
    public ResponseEntity<Void> changeMatchStatus(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody StatusChangeRequest request) {
        matchService.changeMatchStatus(matchId, MatchStatus.valueOf(request.getStatus()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/divisions/{divId}/matchdays/{mdId}/scores/batch")
    @Operation(summary = "節内全試合スコア一括入力")
    public ResponseEntity<Void> batchUpdateScores(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long mdId,
            @Valid @RequestBody BatchScoreRequest request) {
        matchService.batchUpdateScores(tId, divId, mdId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/divisions/{divId}/matchdays/{mdId}/scores/import")
    @Operation(summary = "CSVアップロードによるスコア一括インポート")
    public ResponseEntity<Void> importScores(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long mdId,
            @RequestParam("file") MultipartFile file) {
        List<BatchScoreRequest.MatchScoreEntry> entries = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            // BOM をスキップ
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != '\uFEFF' && firstChar != -1) {
                reader.reset();
            }

            String line = reader.readLine(); // ヘッダー行をスキップ
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 3) continue;

                Long matchId = Long.parseLong(cols[0].trim());
                Integer homeScore = cols[1].trim().isEmpty() ? null : Integer.parseInt(cols[1].trim());
                Integer awayScore = cols[2].trim().isEmpty() ? null : Integer.parseInt(cols[2].trim());
                Integer homeExtra = cols.length > 3 && !cols[3].trim().isEmpty() ? Integer.parseInt(cols[3].trim()) : null;
                Integer awayExtra = cols.length > 4 && !cols[4].trim().isEmpty() ? Integer.parseInt(cols[4].trim()) : null;
                Integer homePk = cols.length > 5 && !cols[5].trim().isEmpty() ? Integer.parseInt(cols[5].trim()) : null;
                Integer awayPk = cols.length > 6 && !cols[6].trim().isEmpty() ? Integer.parseInt(cols[6].trim()) : null;
                String notes = cols.length > 7 ? cols[7].trim() : null;

                entries.add(new BatchScoreRequest.MatchScoreEntry(
                        matchId, homeScore, awayScore, homeExtra, awayExtra,
                        homePk, awayPk, notes, 0L, null));
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        if (!entries.isEmpty()) {
            matchService.batchUpdateScores(tId, divId, mdId, new BatchScoreRequest(entries));
        }
        return ResponseEntity.noContent().build();
    }

    // ===== Roster =====

    @GetMapping("/matches/{matchId}/rosters")
    @Operation(summary = "出場メンバー一覧")
    public ResponseEntity<ApiResponse<List<RosterResponse>>> listRosters(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId) {
        return ResponseEntity.ok(ApiResponse.of(matchService.listRosters(matchId)));
    }

    @PostMapping("/matches/{matchId}/rosters")
    @Operation(summary = "出場メンバー一括登録")
    public ResponseEntity<ApiResponse<List<RosterResponse>>> createRosters(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody CreateRosterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createRosters(matchId, request)));
    }

    @DeleteMapping("/matches/{matchId}/rosters/{rosterId}")
    @Operation(summary = "出場メンバー削除")
    public ResponseEntity<Void> deleteRoster(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long matchId, @PathVariable Long rosterId) {
        matchService.deleteRoster(rosterId);
        return ResponseEntity.noContent().build();
    }
}
