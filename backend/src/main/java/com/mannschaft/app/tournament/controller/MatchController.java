package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.MatchStatus;
import com.mannschaft.app.tournament.TournamentErrorCode;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p>F08.7 順位UI Wave0: 書き込み系（節作成・対戦カード生成・スコア入力・個人成績・試合ステータス・
 * 一括スコア・CSV インポート・出場メンバー登録/削除）に主催組織 ADMIN/DEPUTY_ADMIN の編集権限ガードを
 * 付与した（{@code @accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')}・SYSTEM_ADMIN は常に許可）。
 * 従来は MatchService にもコントローラーにも認可が無く、認証さえあれば他組織の試合結果を改竄できる
 * セキュリティ穴になっていた。</p>
 *
 * <p>F08.7 順位UI 項目③（スコア入力編集権限の細分化）: スコア入力系 EP の認可を主催組織 ADMIN のみから
 * <strong>3-way</strong>（ORG 管理者 / 当該大会の指名スコアキーパー / その試合の参加チーム ADMIN）へ拡張した。
 * 判定は {@link com.mannschaft.app.tournament.scorekeeper.TournamentMatchAccessService}（bean 名
 * {@code tournamentScoreGuard}）に集約し、SpEL では解決できない {@code matchId → participant → teamId} を
 * サービス層で導出する（method-security 維持）。</p>
 * <ul>
 *   <li>単発のスコア入力／個人成績／ステータス変更（{@code #matchId} あり）:
 *       {@code @tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)}（3-way）。</li>
 *   <li>節一括入力／CSV インポート（複数試合横断・{@code #matchId} なし）:
 *       {@code @tournamentScoreGuard.canEnterScoreTournamentWide(authentication, #orgId, #tId)}
 *       （ORG 管理者 / 指名スコアキーパーのみ。参加チーム ADMIN は混在不可ゆえ対象外＝単発入力で対応）。</li>
 * </ul>
 * <p>節作成・対戦カード生成・出場メンバー登録/削除は項目③の対象外（従来どおり主催組織 ADMIN）。</p>
 *
 * <p>F08.7 順位UI Wave0 検分フォロー（B-2a）: GET 系（節一覧・試合詳細・出場メンバー一覧）に
 * F00 共通可視性ガード（{@code contentVisibilityChecker.canView(TOURNAMENT, tId, currentUserId)}・
 * 不可視は IDOR 防止のため 404）を付与した。{@link StandingsController#verifyTournamentVisible} と同流儀。
 * 本コントローラーの全 EP は class 階層パスに親 {@code tId} を持つため、可視性は常にその親 tournament で判定する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "対戦カード・結果管理", description = "F08.7 対戦カード・結果・出場メンバーCRUD")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 大会 visibility ガード（GET 参照系）。認証ユーザー（未認証なら null）が当該 tournament を
     * 閲覧できるか F00 共通可視性 Resolver で判定し、不可視なら 404 を投げる。
     * 可視性は常に親 tournament（class パスの {@code tId}）で判定する。
     *
     * @param tournamentId 大会 ID（class 階層パスの tId）
     */
    private void verifyTournamentVisible(Long tournamentId) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        if (!contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, tournamentId, viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    // ===== Matchday =====

    @GetMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節一覧")
    public ResponseEntity<ApiResponse<List<MatchdayResponse>>> listMatchdays(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        verifyTournamentVisible(tId);
        return ResponseEntity.ok(ApiResponse.of(matchService.listMatchdays(divId)));
    }

    @PostMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節作成")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<MatchdayResponse>> createMatchday(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody CreateMatchdayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createMatchday(divId, request)));
    }

    @PostMapping("/divisions/{divId}/matchdays/generate")
    @Operation(summary = "対戦カード自動生成")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
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
        verifyTournamentVisible(tId);
        return ResponseEntity.ok(ApiResponse.of(matchService.getMatch(matchId)));
    }

    @PatchMapping("/matches/{matchId}/score")
    @Operation(summary = "スコア入力・更新")
    @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")
    public ResponseEntity<ApiResponse<MatchResponse>> updateScore(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody ScoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(matchService.updateScore(tId, matchId, request)));
    }

    @PatchMapping("/matches/{matchId}/player-stats")
    @Operation(summary = "個人成績一括入力")
    @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")
    public ResponseEntity<ApiResponse<MatchResponse>> updatePlayerStats(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody PlayerStatBatchRequest request) {
        return ResponseEntity.ok(ApiResponse.of(matchService.updatePlayerStats(tId, matchId, request)));
    }

    @PatchMapping("/matches/{matchId}/status")
    @Operation(summary = "試合ステータス変更")
    @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")
    public ResponseEntity<Void> changeMatchStatus(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody StatusChangeRequest request) {
        matchService.changeMatchStatus(matchId, MatchStatus.valueOf(request.getStatus()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/divisions/{divId}/matchdays/{mdId}/scores/batch")
    @Operation(summary = "節内全試合スコア一括入力")
    @PreAuthorize("@tournamentScoreGuard.canEnterScoreTournamentWide(authentication, #orgId, #tId)")
    public ResponseEntity<Void> batchUpdateScores(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long mdId,
            @Valid @RequestBody BatchScoreRequest request) {
        matchService.batchUpdateScores(tId, divId, mdId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/divisions/{divId}/matchdays/{mdId}/scores/import")
    @Operation(summary = "CSVアップロードによるスコア一括インポート")
    @PreAuthorize("@tournamentScoreGuard.canEnterScoreTournamentWide(authentication, #orgId, #tId)")
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
        verifyTournamentVisible(tId);
        return ResponseEntity.ok(ApiResponse.of(matchService.listRosters(matchId)));
    }

    @PostMapping("/matches/{matchId}/rosters")
    @Operation(summary = "出場メンバー一括登録")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<List<RosterResponse>>> createRosters(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody CreateRosterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createRosters(matchId, request)));
    }

    @DeleteMapping("/matches/{matchId}/rosters/{rosterId}")
    @Operation(summary = "出場メンバー削除")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<Void> deleteRoster(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long matchId, @PathVariable Long rosterId) {
        matchService.deleteRoster(rosterId);
        return ResponseEntity.noContent().build();
    }
}
