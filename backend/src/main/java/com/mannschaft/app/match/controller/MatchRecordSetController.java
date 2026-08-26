package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchSetRequest;
import com.mannschaft.app.match.dto.MatchSetResponse;
import com.mannschaft.app.match.entity.MatchSetEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchSetService;
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
 * F08.10 セット制スコア（バレーボール）記録・取得コントローラー（sports/04_volleyball.md §4 / §8.1・01 §B.5）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordSetController}（tournament 系に同名なし）＋
 * <b>明示 Bean 名 {@code "matchRecordSetController"}</b> を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}/sets} で持つ
 * （IDOR 1 段目テナントゲート・01 §A.4）。記録認可は Service が {@link MatchAccessService#assertCanRecordTimeline}
 * へ委譲し、閲覧は {@link MatchAccessService#assertCanView}（F00 可視性委譲）で行う。</p>
 *
 * <p>セットスコアの記録は <b>PUT（set_number キーの upsert・冪等）</b>とする
 * （同一セットの再入力＝更新を素直に表現・sports/04 §8.1 数値ステッパー直接入力）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §4 / §8.1 / 01 §B.5</p>
 */
@RestController("matchRecordSetController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}/sets")
@Tag(name = "試合セットスコア", description = "F08.10 セット制スコア記録・取得（バレーボール）")
@RequiredArgsConstructor
public class MatchRecordSetController {

    private final MatchSetService matchSetService;
    private final MatchAccessService matchAccessService;

    @GetMapping
    @Operation(summary = "セットスコア一覧取得（セット番号昇順）")
    public ResponseEntity<ApiResponse<List<MatchSetResponse>>> listSets(
            @PathVariable Long orgId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        List<MatchSetResponse> sets = matchSetService.listSets(matchId, orgId).stream()
                .map(MatchSetResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(sets));
    }

    @PutMapping
    @Operation(summary = "セットスコア記録/更新（set_number キーの upsert）")
    public ResponseEntity<ApiResponse<MatchSetResponse>> recordSet(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchSetRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchSetService.SetScoreCommand command = MatchSetService.SetScoreCommand.builder()
                .setNumber(request.getSetNumber())
                .homePoints(request.getHomePoints())
                .awayPoints(request.getAwayPoints())
                .build();
        MatchSetEntity saved = matchSetService.recordSet(matchId, orgId, actor, command);
        return ResponseEntity.ok(ApiResponse.of(MatchSetResponse.from(saved)));
    }
}
