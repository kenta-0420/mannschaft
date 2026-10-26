package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchResponse;
import com.mannschaft.app.match.dto.MatchScoredComponentRequest;
import com.mannschaft.app.match.dto.MatchScoredComponentResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchScoredComponentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の<b>審判別/種目別採点内訳</b>
 * 記録・取得コントローラー（sports/07_scored.md §4B / §9 / §11 / 01 §B.1.2 / §D.8 / 03 §C.2 / §C.7）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordScoredComponentController}（tournament 系に同名なし）＋
 * 明示 Bean 名を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}/scored-components} で持つ
 * （IDOR 1 段目テナントゲート・01 §A.4）。記録認可は Service が {@link MatchAccessService#assertCanEditMeta}
 * （作成者/記録係/主体チーム ADMIN/DEPUTY＝採点改竄防止・§11 / 03 §C.7）へ委譲し、閲覧は
 * {@link MatchAccessService#assertCanView}（F00 可視性委譲）で行う。</p>
 *
 * <p>採点内訳の記録は <b>PUT（全置換・冪等）</b>とする。サーバーが内訳を HOME/AWAY ごとに集計して
 * {@code matches.home_score}/{@code away_score} へ再導出反映するため（二層正本・§4B.2）、応答は更新後の
 * {@link MatchResponse}（合計点を含む）を返す。内訳明細自体は GET で取得する。</p>
 *
 * <p><b>MVP 合計点直接入力との両立</b>: 内訳を持たない試合は従来どおり {@code PUT /scored-result}
 * （{@code MatchRecordScoredController}）で合計点を直接入力する。本エンドポイントは内訳から合計を導出する経路。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / §9 / §11 / 01 §B.1.2 / §D.8</p>
 */
@RestController("matchRecordScoredComponentController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}/scored-components")
@Tag(name = "試合採点内訳", description = "F08.10 採点競技（フィギュア/体操）審判別/種目別採点内訳（内訳→合計点集計）")
@RequiredArgsConstructor
public class MatchRecordScoredComponentController {

    private final MatchScoredComponentService scoredComponentService;
    private final MatchAccessService matchAccessService;

    @GetMapping
    @Operation(summary = "採点内訳一覧取得（作成時刻昇順・採点競技）")
    public ResponseEntity<ApiResponse<List<MatchScoredComponentResponse>>> listComponents(
            @PathVariable Long orgId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        List<MatchScoredComponentResponse> components =
                scoredComponentService.listComponents(matchId, orgId).stream()
                        .map(MatchScoredComponentResponse::from)
                        .toList();
        return ResponseEntity.ok(ApiResponse.of(components));
    }

    @PutMapping
    @Operation(summary = "採点内訳記録/更新（全置換・内訳→HOME/AWAY 合計点を再導出・採点競技・冪等）")
    public ResponseEntity<ApiResponse<MatchResponse>> recordComponents(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchScoredComponentRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        List<MatchScoredComponentService.ScoredComponentLine> lines = request.getComponents().stream()
                .map(line -> MatchScoredComponentService.ScoredComponentLine.builder()
                        .competitorSide(line.getCompetitorSide())
                        .apparatus(line.getApparatus())
                        .judgeLabel(line.getJudgeLabel())
                        .componentType(line.getComponentType())
                        .pointsScaled(line.getPointsScaled())
                        .build())
                .toList();
        MatchScoredComponentService.ScoredComponentsCommand command =
                MatchScoredComponentService.ScoredComponentsCommand.builder()
                        .lines(lines)
                        .build();
        MatchEntity saved = scoredComponentService.recordComponents(matchId, orgId, actor, command);
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    private MatchResponse toResponse(MatchEntity match, Long viewerUserId) {
        boolean canEdit = matchAccessService.canEditMeta(viewerUserId, match);
        boolean canRecord = matchAccessService.canRecordTimeline(viewerUserId, match);
        return MatchResponse.from(match, canEdit, canRecord);
    }
}
