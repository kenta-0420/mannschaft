package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchResponse;
import com.mannschaft.app.match.dto.MatchScoredResultRequest;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchScoredResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の採点結果記録コントローラー
 * （sports/07_scored.md §4 / §9 / §11 / 01 §B.1.2 / §D.8 / 03 §C.2 / §C.7）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordScoredController}（tournament 系に同名なし）＋
 * 明示 Bean 名を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}} で持つ（IDOR 1 段目テナントゲート・01 §A.4）。
 * 採点記録認可は Service が {@link MatchAccessService#assertCanEditMeta}（作成者/記録係/主体チーム ADMIN/DEPUTY＝
 * 採点改竄防止・§11 / 03 §C.7）へ委譲する。MVP は合計点のみ・2 者対戦（審判別内訳・多人数順位制は後段 Phase）。</p>
 *
 * <ul>
 *   <li>PUT {@code /scored-result}: 採点結果（HOME/AWAY 合計点・整数スケール×1000）の記録/更新（冪等・2 者対戦）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4 / §9 / §11 / 01 §B.1.2 / §D.8</p>
 */
@RestController("matchRecordScoredController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}")
@Tag(name = "試合採点記録", description = "F08.10 採点競技（フィギュア/体操）採点結果（合計点）")
@RequiredArgsConstructor
public class MatchRecordScoredController {

    private final MatchScoredResultService scoredResultService;
    private final MatchAccessService matchAccessService;

    @PutMapping("/scored-result")
    @Operation(summary = "採点結果記録/更新（HOME/AWAY 合計点・整数スケール×1000・採点競技・冪等）")
    public ResponseEntity<ApiResponse<MatchResponse>> recordScore(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchScoredResultRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchScoredResultService.ScoredResultCommand command =
                MatchScoredResultService.ScoredResultCommand.builder()
                        .homeScoreScaled(request.getHomeScoreScaled())
                        .awayScoreScaled(request.getAwayScoreScaled())
                        .build();
        MatchEntity saved = scoredResultService.recordScore(matchId, orgId, actor, command);
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    private MatchResponse toResponse(MatchEntity match, Long viewerUserId) {
        boolean canEdit = matchAccessService.canEditMeta(viewerUserId, match);
        boolean canRecord = matchAccessService.canRecordTimeline(viewerUserId, match);
        return MatchResponse.from(match, canEdit, canRecord);
    }
}
