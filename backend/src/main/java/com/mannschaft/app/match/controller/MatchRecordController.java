package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.dto.ChangeRecordingModeRequest;
import com.mannschaft.app.match.dto.ChangeStatusRequest;
import com.mannschaft.app.match.dto.CreateMatchRequest;
import com.mannschaft.app.match.dto.FinalizeScoreRequest;
import com.mannschaft.app.match.dto.MatchResponse;
import com.mannschaft.app.match.dto.MatchSummaryResponse;
import com.mannschaft.app.match.dto.UpdateMatchRequest;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 試合本体の CRUD・status 遷移・スコア確定・記録モード切替コントローラー（02 §F・03 §C）。
 *
 * <p><b>【Bean 名衝突回避・最重要】</b> 既存 {@code com.mannschaft.app.tournament.controller.FixtureController}（旧 MatchController・Phase5a で改称）と
 * 単純名が異なる（{@code MatchRecordController}）うえ、<b>明示 Bean 名 {@code "matchRecordController"} を付与</b>し
 * デフォルト Bean 名（{@code matchRecordController}）でも tournament 系と衝突しないようにする
 * （Phase2A で {@code MatchService} が同名衝突で ApplicationContext 全滅した教訓）。</p>
 *
 * <h3>二重防御（03 §C.3）</h3>
 * <ul>
 *   <li><b>作成</b>: {@code @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')")}
 *       （第二防御）。第一防御として {@code teamId}/{@code createdBy} はサーバー導出（マスアサインメント防止・03 §C.4a）。</li>
 *   <li><b>取得</b>: {@link MatchAccessService#assertCanView}（F00 可視性へ委譲）。</li>
 *   <li><b>更新/削除/status/score/mode</b>: {@link MatchAccessService#assertCanEditMeta}（Service 第一防御）。</li>
 * </ul>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/teams/{teamId}/matches} で持つ（既存 tournament の慣習）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F
 *   / 03_permissions_and_recording_modes.md §C</p>
 */
@RestController("matchRecordController")
@RequestMapping("/api/v1/organizations/{orgId}/teams/{teamId}/matches")
@Tag(name = "試合記録", description = "F08.10 試合 CRUD・status・スコア確定・記録モード")
@RequiredArgsConstructor
public class MatchRecordController {

    private final MatchService matchService;
    private final MatchAccessService matchAccessService;

    @PostMapping
    @Operation(summary = "試合作成")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId.value(), 'TEAM')")
    public ResponseEntity<ApiResponse<MatchResponse>> createMatch(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @Valid @RequestBody CreateMatchRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        // teamId / createdBy / organizationId はサーバー導出（DTO から受けない・マスアサインメント防止・03 §C.4a）
        MatchService.CreateCommand command = MatchService.CreateCommand.builder()
                .organizationId(orgId.value())
                .teamId(teamId.value())
                .createdBy(actor)
                .sport(request.getSport())
                .kind(request.getKind())
                .tournamentFixtureId(request.getTournamentFixtureId())
                .scheduleId(request.getScheduleId())
                .homeAway(request.getHomeAway())
                .opponentTeamId(request.getOpponentTeamId())
                .opponentName(request.getOpponentName())
                .kickoffAt(request.getKickoffAt())
                .venue(request.getVenue())
                .durationMinutes(request.getDurationMinutes())
                .periodFormat(request.getPeriodFormat())
                .hasScorekeeper(request.isHasScorekeeper())
                .scorekeeperUserId(request.getScorekeeperUserId())
                .notes(request.getNotes())
                .build();
        MatchEntity saved = matchService.create(command, actor);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(toResponse(saved, actor)));
    }

    @GetMapping
    @Operation(summary = "チーム試合一覧（メンバー以上・ページング・kind/status/期間フィルタ）")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId.value(), 'TEAM')")
    public ResponseEntity<PagedResponse<MatchSummaryResponse>> listMatches(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) MatchStatus status,
            @RequestParam(required = false) Sport sport,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long actor = SecurityUtils.getCurrentUserId();
        // 認可（第一防御）・テナント絞り込みは Service / Repository に集約する（03 §C.3.1）
        MatchService.ListFilter filter = MatchService.ListFilter.builder()
                .kind(kind)
                .status(status)
                .sport(sport)
                .from(from)
                .to(to)
                .build();
        Page<MatchSummaryResponse> result = matchService.listMatches(
                orgId.value(), teamId.value(), actor, filter, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @GetMapping("/by-schedule/{scheduleId}")
    @Operation(summary = "カレンダー予定から既存試合を解決（入口④・二重起票防止）")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId.value(), 'TEAM')")
    public ResponseEntity<ApiResponse<MatchSummaryResponse>> resolveBySchedule(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable Long scheduleId) {
        Long actor = SecurityUtils.getCurrentUserId();
        // 既存があれば FE は live を開く・無ければ作成する。存在しない場合は 200 + data:null を返し、
        // FE が単純に null 判定できるようにする（404 を正常フローとして扱わせない・症状を隠さない）。
        MatchSummaryResponse summary = matchService
                .resolveByScheduleId(orgId.value(), teamId.value(), actor, scheduleId)
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(summary));
    }

    @GetMapping("/by-fixture/{fixtureId}")
    @Operation(summary = "大会の対戦カードから既存試合を解決（入口①・二重起票防止）")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId.value(), 'TEAM')")
    public ResponseEntity<ApiResponse<MatchSummaryResponse>> resolveByFixture(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable Long fixtureId) {
        Long actor = SecurityUtils.getCurrentUserId();
        // 既存があれば FE は live を開く・無ければ作成する。存在しない場合は 200 + data:null を返し、
        // FE が単純に null 判定できるようにする（404 を正常フローとして扱わせない・症状を隠さない）。
        // 入口④ by-schedule と完全対称（04 §G.1a-2）。
        MatchSummaryResponse summary = matchService
                .resolveByFixtureId(orgId.value(), teamId.value(), actor, fixtureId)
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.of(summary));
    }

    @GetMapping("/{matchId}")
    @Operation(summary = "試合詳細")
    public ResponseEntity<ApiResponse<MatchResponse>> getMatch(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        // 閲覧可視性は F00 へ委譲（不可は 404・存在を漏らさない）
        matchAccessService.assertCanView(actor, matchId);
        MatchEntity match = matchService.getMatchOrThrow(matchId, orgId.value());
        return ResponseEntity.ok(ApiResponse.of(toResponse(match, actor)));
    }

    @PatchMapping("/{matchId}")
    @Operation(summary = "試合メタ更新")
    public ResponseEntity<ApiResponse<MatchResponse>> updateMatch(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId,
            @Valid @RequestBody UpdateMatchRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchService.UpdateMetaCommand command = MatchService.UpdateMetaCommand.builder()
                .homeAway(request.getHomeAway())
                .opponentTeamId(request.getOpponentTeamId())
                .opponentName(request.getOpponentName())
                .kickoffAt(request.getKickoffAt())
                .venue(request.getVenue())
                .durationMinutes(request.getDurationMinutes())
                .periodFormat(request.getPeriodFormat())
                .notes(request.getNotes())
                .build();
        MatchEntity saved = matchService.updateMeta(matchId, orgId.value(), actor, command);
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    @PatchMapping("/{matchId}/status")
    @Operation(summary = "status 遷移（COMPLETED で確定再計算）")
    public ResponseEntity<ApiResponse<MatchResponse>> changeStatus(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId,
            @Valid @RequestBody ChangeStatusRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity saved = matchService.changeStatus(matchId, orgId.value(), actor, request.getStatus());
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    @PatchMapping("/{matchId}/score")
    @Operation(summary = "最終スコア確定")
    public ResponseEntity<ApiResponse<MatchResponse>> finalizeScore(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId,
            @Valid @RequestBody FinalizeScoreRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity saved = matchService.finalizeScore(matchId, orgId.value(), actor,
                request.getHomeScore(), request.getAwayScore(),
                request.getHomePenaltyScore(), request.getAwayPenaltyScore());
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    @PatchMapping("/{matchId}/recording-mode")
    @Operation(summary = "記録モード切替（公式戦⇔共同記録）")
    public ResponseEntity<ApiResponse<MatchResponse>> changeRecordingMode(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId,
            @Valid @RequestBody ChangeRecordingModeRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity saved = matchService.changeRecordingMode(matchId, orgId.value(), actor,
                request.isHasScorekeeper(), request.getScorekeeperUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(saved, actor)));
    }

    @DeleteMapping("/{matchId}")
    @Operation(summary = "試合論理削除")
    public ResponseEntity<Void> deleteMatch(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchService.softDelete(matchId, orgId.value(), actor);
        return ResponseEntity.noContent().build();
    }

    /** Entity → DTO（権限フラグは閲覧者基準で算出・所有列は隠す・03 §C.2）。 */
    private MatchResponse toResponse(MatchEntity match, Long viewerUserId) {
        boolean canEdit = matchAccessService.canEditMeta(viewerUserId, match);
        boolean canRecord = matchAccessService.canRecordTimeline(viewerUserId, match);
        return MatchResponse.from(match, canEdit, canRecord);
    }
}
