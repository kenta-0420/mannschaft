package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchBoardCreateRequest;
import com.mannschaft.app.match.dto.MatchResponse;
import com.mannschaft.app.match.dto.MatchTurnResultRequest;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchTurnResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.10 ターン制（将棋/囲碁）の対局結果記録・団体戦の親子ボードコントローラー
 * （sports/05_shogi.md §4 / §8.1 / sports/06_go.md §4 / 01 §B.1.2 / §B.6 / 03 §C.2a / §C.4）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordTurnController}（tournament 系に同名なし）＋
 * 明示 Bean 名を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}} で持つ（IDOR 1 段目テナントゲート・01 §A.4）。
 * 記録認可は Service が {@link MatchAccessService#assertCanRecordTimeline}（§C.2a の TURN_BASED 類型分岐＝対局者本人 or
 * チーム ADMIN or 記録係）へ委譲する。閲覧は {@link MatchAccessService#assertCanView}（F00 委譲）。
 * 団体戦の子ボードは親 ID スコープの二段アクセスで取得し、子直引きの越境を遮断する（§C.4）。</p>
 *
 * <ul>
 *   <li>PUT {@code /result}: 対局結果（勝者・勝ち方・総手数）の記録/更新（冪等・個人戦 or 子ボード）。</li>
 *   <li>POST {@code /boards}: 団体戦の子ボード作成（親配下）。</li>
 *   <li>GET {@code /boards}: 団体戦の子ボード一覧（親 ID スコープ）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/05_shogi.md §4 / §8.1 / 01 §B.6 / 03 §C.2a / §C.4</p>
 */
@RestController("matchRecordTurnController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}")
@Tag(name = "試合ターン制記録", description = "F08.10 ターン制（将棋/囲碁）対局結果・団体戦ボード")
@RequiredArgsConstructor
public class MatchRecordTurnController {

    private final MatchTurnResultService turnResultService;
    private final MatchAccessService matchAccessService;

    @PutMapping("/result")
    @Operation(summary = "対局結果記録/更新（勝者・勝ち方・総手数・ターン制・冪等）")
    public ResponseEntity<ApiResponse<MatchResponse>> recordResult(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchTurnResultRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchTurnResultService.TurnResultCommand command = MatchTurnResultService.TurnResultCommand.builder()
                .winnerSide(request.getWinnerSide())
                .winMethod(request.getWinMethod())
                .totalMoves(request.getTotalMoves())
                .build();
        MatchEntity saved = turnResultService.recordIndividualResult(matchId, orgId, actor, command);
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    @PostMapping("/boards")
    @Operation(summary = "団体戦の子ボード作成（親配下・将棋/囲碁）")
    public ResponseEntity<ApiResponse<MatchResponse>> createBoard(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchBoardCreateRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity board = turnResultService.createBoard(
                matchId, orgId, actor,
                request.getBoardNumber(), request.getOpponentTeamId(), request.getOpponentName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(toResponse(board, actor)));
    }

    @GetMapping("/boards")
    @Operation(summary = "団体戦の子ボード一覧（親 ID スコープ・board_number 昇順）")
    public ResponseEntity<ApiResponse<List<MatchResponse>>> listBoards(
            @PathVariable Long orgId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        // 閲覧可視性は F00 へ委譲（親 match）。
        matchAccessService.assertCanView(actor, matchId);
        List<MatchResponse> boards = turnResultService.listBoards(matchId, orgId).stream()
                .map(b -> toResponse(b, actor))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(boards));
    }

    private MatchResponse toResponse(MatchEntity match, Long viewerUserId) {
        boolean canEdit = matchAccessService.canEditMeta(viewerUserId, match);
        boolean canRecord = matchAccessService.canRecordTimeline(viewerUserId, match);
        return MatchResponse.from(match, canEdit, canRecord);
    }
}
