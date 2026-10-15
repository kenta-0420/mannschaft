package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.dto.MatchScoreEntryRequest;
import com.mannschaft.app.match.dto.MatchScoreEntryResponse;
import com.mannschaft.app.match.entity.MatchScoreEntryEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchScoreEntryService;
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
 * F08.10 採点競技（フィギュアスケート/体操＝第 4 状態モデル類型 SCORED）の<b>多人数順位制の出場者エントリ</b>
 * 記録・取得コントローラー（sports/07_scored.md §5B / §9 / §11 / 01 §B.1.2 / §D.8 / 03 §C.2 / §C.7）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordScoreEntryController}（tournament 系に同名なし）＋
 * 明示 Bean 名を付与（feedback_spring_bean_name_collision_same_simplename）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}/score-entries} で持つ
 * （IDOR 1 段目テナントゲート・01 §A.4）。記録認可は Service が {@link MatchAccessService#assertCanEditMeta}
 * （作成者/記録係/主体チーム ADMIN/DEPUTY＝採点改竄防止・§11 / 03 §C.7）へ委譲し、閲覧は
 * {@link MatchAccessService#assertCanView}（F00 可視性委譲）で行う。</p>
 *
 * <p>出場者エントリの記録は <b>PUT（全置換・冪等）</b>とする。サーバーが合計点降順で順位を算出し、
 * 補助として最上位合計点を {@code matches.home_score} へ再導出反映するため（二層正本・§5B.2）、応答は
 * 順位算出済みの出場者エントリ一覧（順位順）を返す。</p>
 *
 * <p><b>MVP 2 者対戦・審判内訳との両立</b>: 2 者対戦の合計点は {@code PUT /scored-result}、審判別内訳は
 * {@code PUT /scored-components} を用いる。本エンドポイントは多人数順位制（後段 Phase の本来形）の追加経路。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B / §9 / §11 / 01 §B.1.2 / §D.8</p>
 */
@RestController("matchRecordScoreEntryController")
@RequestMapping("/api/v1/organizations/{orgId}/matches/{matchId}/score-entries")
@Tag(name = "試合採点エントリ", description = "F08.10 採点競技（フィギュア/体操）多人数順位制の出場者エントリ（合計点→順位算出）")
@RequiredArgsConstructor
public class MatchRecordScoreEntryController {

    private final MatchScoreEntryService scoreEntryService;
    private final MatchAccessService matchAccessService;

    @GetMapping
    @Operation(summary = "出場者エントリ一覧取得（順位昇順・同順位は合計点降順・採点競技）")
    public ResponseEntity<ApiResponse<List<MatchScoreEntryResponse>>> listEntries(
            @PathVariable Long orgId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        List<MatchScoreEntryResponse> entries =
                scoreEntryService.listEntries(matchId, orgId).stream()
                        .map(MatchScoreEntryResponse::from)
                        .toList();
        return ResponseEntity.ok(ApiResponse.of(entries));
    }

    @PutMapping
    @Operation(summary = "出場者エントリ記録/更新（全置換・合計点降順で順位算出・採点競技・冪等）")
    public ResponseEntity<ApiResponse<List<MatchScoreEntryResponse>>> recordEntries(
            @PathVariable Long orgId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchScoreEntryRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        List<MatchScoreEntryService.ScoreEntryLine> lines = request.getEntries().stream()
                .map(line -> MatchScoreEntryService.ScoreEntryLine.builder()
                        .competitorUserId(line.getCompetitorUserId())
                        .competitorName(line.getCompetitorName())
                        .competitorTeamId(line.getCompetitorTeamId())
                        .totalScaled(line.getTotalScaled())
                        .build())
                .toList();
        MatchScoreEntryService.ScoreEntriesCommand command =
                MatchScoreEntryService.ScoreEntriesCommand.builder()
                        .lines(lines)
                        .build();
        List<MatchScoreEntryEntity> saved = scoreEntryService.recordEntries(matchId, orgId, actor, command);
        List<MatchScoreEntryResponse> response = saved.stream()
                .map(MatchScoreEntryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
