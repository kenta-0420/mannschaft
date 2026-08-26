package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.FixtureStatus;
import com.mannschaft.app.tournament.dto.BatchScoreRequest;
import com.mannschaft.app.tournament.dto.CreateMatchdayRequest;
import com.mannschaft.app.tournament.dto.CreateRosterRequest;
import com.mannschaft.app.tournament.dto.FixtureResponse;
import com.mannschaft.app.tournament.dto.MatchdayResponse;
import com.mannschaft.app.tournament.dto.PlayerStatBatchRequest;
import com.mannschaft.app.tournament.dto.RosterResponse;
import com.mannschaft.app.tournament.dto.ScoreUpdateRequest;
import com.mannschaft.app.tournament.dto.StatusChangeRequest;
import com.mannschaft.app.tournament.service.FixtureService;
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
 * 従来は FixtureService にもコントローラーにも認可が無く、認証さえあれば他組織の試合結果を改竄できる
 * セキュリティ穴になっていた。</p>
 *
 * <p>F08.7 順位UI 項目③（スコア入力編集権限の細分化）: スコア入力系 EP の認可を主催組織 ADMIN のみから
 * <strong>3-way</strong>（ORG 管理者 / 当該大会の指名スコアキーパー / その試合の参加チーム ADMIN）へ拡張した。
 * 判定は {@link com.mannschaft.app.tournament.scorekeeper.TournamentFixtureAccessService}（bean 名
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
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: {@code @PreAuthorize} は path の {@code orgId} のみで
 * 判定するため、他組織 ADMIN が自組織 URL に他組織の {@code tId}/{@code divId}/{@code matchId} を
 * 埋め込む BOLA を単独では防げない（例: orgB ADMIN が {@code /organizations/{orgB}/tournaments/{tPubA}}
 * のように orgA の大会 ID を指定）。{@link FixtureService} 側で {@code tId→orgId}・{@code divId→tId}・
 * {@code matchId→tId}・{@code rosterId→matchId} の実体束縛を必ず検証し、不一致は 404（存在秘匿）で遮断する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "対戦カード・結果管理", description = "F08.7 対戦カード・結果・出場メンバーCRUD")
@RequiredArgsConstructor
public class FixtureController {

    private final FixtureService matchService;

    // ===== Matchday =====

    @GetMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節一覧")
    public ResponseEntity<ApiResponse<List<MatchdayResponse>>> listMatchdays(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(matchService.listMatchdays(tId, divId, viewerUserId)));
    }

    @PostMapping("/divisions/{divId}/matchdays")
    @Operation(summary = "節作成")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<MatchdayResponse>> createMatchday(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody CreateMatchdayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createMatchday(
                        orgId, tId, divId, SecurityUtils.getCurrentUserId(), request)));
    }

    @PostMapping("/divisions/{divId}/matchdays/generate")
    @Operation(summary = "対戦カード自動生成")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<List<MatchdayResponse>>> generateMatchdays(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.generateMatchdays(
                        orgId, tId, divId, SecurityUtils.getCurrentUserId())));
    }

    // ===== Match =====

    @GetMapping("/matches/{matchId}")
    @Operation(summary = "試合詳細")
    public ResponseEntity<ApiResponse<FixtureResponse>> getMatch(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(matchService.getMatch(tId, matchId, viewerUserId)));
    }

    @PatchMapping("/matches/{matchId}/score")
    @Operation(summary = "スコア入力・更新")
    @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")
    public ResponseEntity<ApiResponse<FixtureResponse>> updateScore(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody ScoreUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(matchService.updateScore(tId, matchId, request)));
    }

    @PatchMapping("/matches/{matchId}/player-stats")
    @Operation(summary = "個人成績一括入力")
    @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")
    public ResponseEntity<ApiResponse<FixtureResponse>> updatePlayerStats(
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
        matchService.changeMatchStatus(tId, matchId, FixtureStatus.valueOf(request.getStatus()));
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

                // 列順: matchId, homeScore, awayScore, homePenaltyScore, awayPenaltyScore, notes
                // 延長別スコア列は Phase 5b-3 で廃止（延長得点は本戦スコアへ合算済み・05 §H.1 移行表）。
                Long matchId = Long.parseLong(cols[0].trim());
                Integer homeScore = cols[1].trim().isEmpty() ? null : Integer.parseInt(cols[1].trim());
                Integer awayScore = cols[2].trim().isEmpty() ? null : Integer.parseInt(cols[2].trim());
                Integer homePk = cols.length > 3 && !cols[3].trim().isEmpty() ? Integer.parseInt(cols[3].trim()) : null;
                Integer awayPk = cols.length > 4 && !cols[4].trim().isEmpty() ? Integer.parseInt(cols[4].trim()) : null;
                String notes = cols.length > 5 ? cols[5].trim() : null;

                entries.add(new BatchScoreRequest.MatchScoreEntry(
                        matchId, homeScore, awayScore,
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
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.of(matchService.listRosters(tId, matchId, viewerUserId)));
    }

    @PostMapping("/matches/{matchId}/rosters")
    @Operation(summary = "出場メンバー一括登録")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<List<RosterResponse>>> createRosters(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody CreateRosterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(matchService.createRosters(
                        orgId, tId, matchId, SecurityUtils.getCurrentUserId(), request)));
    }

    @DeleteMapping("/matches/{matchId}/rosters/{rosterId}")
    @Operation(summary = "出場メンバー削除")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<Void> deleteRoster(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long matchId, @PathVariable Long rosterId) {
        matchService.deleteRoster(orgId, tId, matchId, rosterId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
