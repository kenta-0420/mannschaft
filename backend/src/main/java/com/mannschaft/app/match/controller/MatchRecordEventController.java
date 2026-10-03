package com.mannschaft.app.match.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchEventRequest;
import com.mannschaft.app.match.dto.MatchEventResponse;
import com.mannschaft.app.match.dto.MatchEventsResponse;
import com.mannschaft.app.match.dto.PlayerAppearanceResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchEventService;
import com.mannschaft.app.match.service.MatchService;
import com.mannschaft.app.match.service.MatchStatsAggregationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.10 タイムラインイベント記録・更新・削除＋試合内取得（events/appearances）コントローラー（02 §F.4・03 §C.4）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchRecordEventController}（tournament 系に同名なし）＋
 * <b>明示 Bean 名 {@code "matchRecordEventController"}</b> を付与（Phase2A の同名衝突教訓）。</p>
 *
 * <h3>recorded_by_team_id のサーバー導出（03 §C.4a・マスアサインメント防止）</h3>
 * <p>権限列 {@code recorded_by_team_id} は Request DTO に含めず、<b>認証主体が ADMIN/DEPUTY であるチーム</b>
 * （match の {@code team_id} or {@code opponent_team_id} のうち principal の所属）から導出する。公式戦（記録係）は
 * {@code team_side} に対応する側（HOME→team_id / AWAY→opponent_team_id・未登録相手は team_id 代行）を名義とする。
 * 「principal が当該サイドを記録してよいか」は {@link MatchAccessService#canRecordTimeline} で認可し、
 * 名義↔サイドの整合不変条件は Service の {@code validateSideOwnership} が二重防御する（責務分界）。</p>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/matches/{matchId}/...} で持つ（IDOR 1 段目テナントゲート・01 §A.4）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F.4
 *   / 03_permissions_and_recording_modes.md §C.4 / §C.4a</p>
 */
@RestController("matchRecordEventController")
@RequestMapping({
        "/api/v1/organizations/{orgId}/matches/{matchId}",
        "/api/v1/teams/{teamId}/matches/{matchId}"
})
@Tag(name = "試合イベント", description = "F08.10 タイムラインイベント記録・取得")
@RequiredArgsConstructor
public class MatchRecordEventController {

    private static final String SCOPE_TEAM = "TEAM";

    private final MatchService matchService;
    private final MatchEventService matchEventService;
    private final MatchAccessService matchAccessService;
    private final MatchStatsAggregationService aggregationService;
    private final AccessControlService accessControlService;

    // ─────────────────────────────────────────────
    // 取得（F.4・閲覧可視性は F00 へ委譲）
    // ─────────────────────────────────────────────

    @GetMapping("/events")
    @Operation(summary = "試合内タイムライン取得（スコア整合警告込み）")
    public ResponseEntity<ApiResponse<MatchEventsResponse>> listEvents(
            @PathVariable(required = false) Long orgId,
            @PathVariable(required = false) Long teamId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        return ResponseEntity.ok(ApiResponse.of(aggregationService.getMatchEvents(matchId, orgId, teamId)));
    }

    @GetMapping("/appearances")
    @Operation(summary = "試合内出場記録一覧（computed_minutes 込み）")
    public ResponseEntity<ApiResponse<List<PlayerAppearanceResponse>>> listAppearances(
            @PathVariable(required = false) Long orgId,
            @PathVariable(required = false) Long teamId,
            @PathVariable UUID matchId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchAccessService.assertCanView(actor, matchId);
        return ResponseEntity.ok(ApiResponse.of(aggregationService.getMatchAppearances(matchId, orgId, teamId)));
    }

    // ─────────────────────────────────────────────
    // 記録・更新・削除（C.4・recorded_by_team_id はサーバー導出）
    // ─────────────────────────────────────────────

    @PostMapping("/events")
    @Operation(summary = "イベント記録")
    public ResponseEntity<ApiResponse<MatchEventResponse>> recordEvent(
            @PathVariable(required = false) Long orgId,
            @PathVariable(required = false) Long teamId,
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchEventRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity match = matchService.getMatchOrThrow(matchId, orgId, teamId);
        // 一次認可（principal がこの試合に記録してよいか）。Service 側でも assertCanRecordTimeline が再検証する。
        matchAccessService.assertCanRecordTimeline(actor, match);

        Long recordedByTeamId = deriveRecordedByTeamId(actor, match, request.getTeamSide());
        MatchEventService.EventCommand command = toCommand(request, recordedByTeamId);
        MatchEventEntity saved = matchEventService.record(matchId, orgId, teamId, actor, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(MatchEventResponse.from(saved)));
    }

    @PatchMapping("/events/{eventId}")
    @Operation(summary = "イベント更新")
    public ResponseEntity<ApiResponse<MatchEventResponse>> updateEvent(
            @PathVariable(required = false) Long orgId,
            @PathVariable(required = false) Long teamId,
            @PathVariable UUID matchId,
            @PathVariable UUID eventId,
            @Valid @RequestBody MatchEventRequest request) {
        Long actor = SecurityUtils.getCurrentUserId();
        MatchEntity match = matchService.getMatchOrThrow(matchId, orgId, teamId);
        matchAccessService.assertCanRecordTimeline(actor, match);
        // 更新では recorded_by_team_id は Service 側が既存値を維持する（DTO 由来の名義は渡さない）
        MatchEventService.EventCommand command = toCommand(request, null);
        MatchEventEntity saved = matchEventService.update(matchId, eventId, orgId, teamId, actor, command);
        return ResponseEntity.ok(ApiResponse.of(MatchEventResponse.from(saved)));
    }

    @DeleteMapping("/events/{eventId}")
    @Operation(summary = "イベント削除")
    public ResponseEntity<Void> deleteEvent(
            @PathVariable(required = false) Long orgId,
            @PathVariable(required = false) Long teamId,
            @PathVariable UUID matchId,
            @PathVariable UUID eventId) {
        Long actor = SecurityUtils.getCurrentUserId();
        matchEventService.delete(matchId, eventId, orgId, teamId, actor);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // recorded_by_team_id のサーバー導出（03 §C.4a）
    // ─────────────────────────────────────────────

    /**
     * 認証主体の記録名義チーム（{@code recorded_by_team_id}）を導出する（マスアサインメント防止）。
     *
     * <ul>
     *   <li><b>公式戦（記録係）</b>: 記録係は両 side を代行記録できるため、{@code team_side} に対応するチームを名義にする
     *       （HOME→team_id / AWAY→opponent_team_id・未登録相手は team_id 代行・03 §未解決 4）。</li>
     *   <li><b>共同記録</b>: principal が ADMIN/DEPUTY であるチーム（team_id or opponent_team_id）を名義にする。
     *       自サイド以外を自名義で記録できない（Service の {@code validateSideOwnership} が整合を二重検証）。</li>
     * </ul>
     *
     * <p>導出できない（principal がいずれのチームの ADMIN/DEPUTY でもない非記録係）場合は 403。</p>
     */
    private Long deriveRecordedByTeamId(Long actor, MatchEntity match, TeamSide teamSide) {
        boolean isScorekeeper = match.isHasScorekeeper()
                && actor.equals(match.getScorekeeperUserId());
        if (isScorekeeper) {
            // 記録係は team_side に対応する側の名義で記録（未登録相手の AWAY は主体チームが代行）
            if (teamSide == TeamSide.AWAY && match.getOpponentTeamId() != null) {
                return match.getOpponentTeamId();
            }
            return match.getTeamId();
        }
        // 共同記録: principal が ADMIN/DEPUTY のチームを名義にする
        if (match.getTeamId() != null
                && accessControlService.isAdminOrAbove(actor, match.getTeamId(), SCOPE_TEAM)) {
            return match.getTeamId();
        }
        if (match.getOpponentTeamId() != null
                && accessControlService.isAdminOrAbove(actor, match.getOpponentTeamId(), SCOPE_TEAM)) {
            return match.getOpponentTeamId();
        }
        // 記録権限のあるチーム名義を導出できない（403・症状を隠さない）
        throw new BusinessException(MatchErrorCode.MATCH_010);
    }

    private MatchEventService.EventCommand toCommand(MatchEventRequest request, Long recordedByTeamId) {
        return MatchEventService.EventCommand.builder()
                .minute(request.getMinute())
                .stoppageMinute(request.getStoppageMinute())
                .period(request.getPeriod())
                .eventType(request.getEventType())
                .cardReasonCode(request.getCardReasonCode())
                .customLabel(request.getCustomLabel())
                .teamSide(request.getTeamSide())
                .playerUserId(request.getPlayerUserId())
                .playerName(request.getPlayerName())
                .jerseyNumber(request.getJerseyNumber())
                .relatedPlayerUserId(request.getRelatedPlayerUserId())
                .relatedPlayerName(request.getRelatedPlayerName())
                .note(request.getNote())
                .linkedEventId(request.getLinkedEventId())
                .detail(request.getDetail())
                .recordedByTeamId(recordedByTeamId)
                .sortSeq(request.getSortSeq())
                .build();
    }
}
