package com.mannschaft.app.match.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.dto.TeamMatchStatsResponse;
import com.mannschaft.app.match.dto.UserMatchStatsResponse;
import com.mannschaft.app.match.dto.UserMatchTimelineEntry;
import com.mannschaft.app.match.service.MatchStatsAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * F08.10 集計取得コントローラー（個人キャリア／個人タイムライン／チーム統計・02 §F・03 §C.4）。
 *
 * <p><b>【Bean 名衝突回避】</b> 単純名 {@code MatchStatsController}（tournament 系に同名なし）＋
 * <b>明示 Bean 名 {@code "matchStatsController"}</b> を付与（Phase2A の同名衝突教訓）。</p>
 *
 * <h3>認可（02 §F.1 / §F.3・03 §C.4）</h3>
 * <ul>
 *   <li><b>個人統計（チーム横断）</b> {@code /users/{userId}/match-stats}: <b>本人のみ</b>（{@code userId==self}）。</li>
 *   <li><b>個人統計（team スコープ・他者閲覧）</b> {@code /users/{userId}/teams/{teamId}/match-stats}:
 *       本人 or（<b>閲覧者が当該チーム ADMIN/DEPUTY ＋ 対象 userId が当該チーム所属</b>）or
 *       <b>F19.1 公開設定（{@code public_profile_enabled}）が ON かつ 閲覧者が当該チームメンバー</b>。</li>
 *   <li><b>チーム統計</b> {@code /teams/{teamId}/match-stats}: 当該チームのメンバー以上。
 *       選手別ランキング（{@code playerRankings}）は <b>MEMBER 以上（SUPPORTER 除外）</b>（02 §F.3）。</li>
 * </ul>
 *
 * <p>テナント文脈はパス {@code /organizations/{orgId}/...} で持つ（テナント越境遮断・02 §F）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/02_playing_time_and_aggregation.md §F.1 / §F.2 / §F.3</p>
 */
@RestController("matchStatsController")
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "試合統計", description = "F08.10 個人・チーム集計")
@RequiredArgsConstructor
public class MatchStatsController {

    private static final String SCOPE_TEAM = "TEAM";
    private static final int DEFAULT_RANKING_LIMIT = 20;

    private final MatchStatsAggregationService aggregationService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    // ─────────────────────────────────────────────
    // F.1 個人キャリア統計（本人・チーム横断）
    // ─────────────────────────────────────────────

    @GetMapping("/users/{userId}/match-stats")
    @Operation(summary = "個人キャリア統計（本人のみ・チーム横断）")
    public ResponseEntity<ApiResponse<UserMatchStatsResponse>> getUserStats(
            @PathVariable OrgScopeId orgId,
            @PathVariable Long userId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) Sport sport) {
        Long viewer = SecurityUtils.getCurrentUserId();
        // チーム横断は本人限定（teamId 無し・02 §F.1）。他者は 403。
        if (!viewer.equals(userId)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
        return ResponseEntity.ok(ApiResponse.of(
                aggregationService.aggregateUserStats(orgId.value(), userId, null, from, to, kind, sport)));
    }

    @GetMapping("/users/{userId}/match-stats/timeline")
    @Operation(summary = "個人タイムライン（本人のみ・チーム横断・ページング）")
    public ResponseEntity<PagedResponse<UserMatchTimelineEntry>> getUserTimeline(
            @PathVariable OrgScopeId orgId,
            @PathVariable Long userId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) Sport sport,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long viewer = SecurityUtils.getCurrentUserId();
        if (!viewer.equals(userId)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
        return timelineResponse(orgId.value(), userId, null, from, to, kind, sport, page, size);
    }

    // ─────────────────────────────────────────────
    // F.1 個人統計（team スコープ・他者閲覧）
    // ─────────────────────────────────────────────

    @GetMapping("/users/{userId}/teams/{teamId}/match-stats")
    @Operation(summary = "個人キャリア統計（team スコープ・他者閲覧）")
    public ResponseEntity<ApiResponse<UserMatchStatsResponse>> getUserTeamStats(
            @PathVariable OrgScopeId orgId,
            @PathVariable Long userId,
            @PathVariable TeamScopeId teamId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) Sport sport) {
        Long viewer = SecurityUtils.getCurrentUserId();
        assertCanViewOtherUserStats(viewer, userId, teamId.value());
        return ResponseEntity.ok(ApiResponse.of(
                aggregationService.aggregateUserStats(orgId.value(), userId, teamId.value(), from, to, kind, sport)));
    }

    @GetMapping("/users/{userId}/teams/{teamId}/match-stats/timeline")
    @Operation(summary = "個人タイムライン（team スコープ・他者閲覧・ページング）")
    public ResponseEntity<PagedResponse<UserMatchTimelineEntry>> getUserTeamTimeline(
            @PathVariable OrgScopeId orgId,
            @PathVariable Long userId,
            @PathVariable TeamScopeId teamId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) Sport sport,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long viewer = SecurityUtils.getCurrentUserId();
        assertCanViewOtherUserStats(viewer, userId, teamId.value());
        return timelineResponse(orgId.value(), userId, teamId.value(), from, to, kind, sport, page, size);
    }

    // ─────────────────────────────────────────────
    // F.3 チーム統計
    // ─────────────────────────────────────────────

    @GetMapping("/teams/{teamId}/match-stats")
    @Operation(summary = "チーム統計（メンバー以上・ランキングは MEMBER 以上＝SUPPORTER 除外）")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId.value(), 'TEAM')")
    public ResponseEntity<ApiResponse<TeamMatchStatsResponse>> getTeamStats(
            @PathVariable OrgScopeId orgId,
            @PathVariable TeamScopeId teamId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) MatchKind kind,
            @RequestParam(required = false) Sport sport) {
        Long viewer = SecurityUtils.getCurrentUserId();
        // 第一防御（Service 層相当の明示判定）: メンバー以上であること
        if (!accessControlService.isMember(viewer, teamId.value(), SCOPE_TEAM)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
        // playerRankings は MEMBER 以上（SUPPORTER 除外・02 §F.3）。SUPPORTER はランキングを隠す。
        boolean includeRankings = accessControlService.hasRoleOrAbove(viewer, teamId.value(), SCOPE_TEAM, "MEMBER");
        return ResponseEntity.ok(ApiResponse.of(
                aggregationService.aggregateTeamStats(
                        orgId.value(), teamId.value(), from, to, kind, sport, includeRankings, DEFAULT_RANKING_LIMIT)));
    }

    // ─────────────────────────────────────────────
    // 認可ヘルパー
    // ─────────────────────────────────────────────

    /**
     * 他者個人統計（team スコープ）の閲覧可否を検証する（02 §F.1・本人/管理者以外不可＋F19.1 連動）。
     *
     * <ol>
     *   <li>本人なら許可。</li>
     *   <li>閲覧者が当該チーム ADMIN/DEPUTY ＋ 対象 userId が当該チーム所属 → 許可（二重検証）。</li>
     *   <li>対象ユーザーの F19.1 公開設定（{@code public_profile_enabled}）が ON ＋ 閲覧者が当該チームメンバー
     *       ＋ 対象 userId が当該チーム所属 → 許可。</li>
     *   <li>いずれも満たさなければ 403。</li>
     * </ol>
     */
    private void assertCanViewOtherUserStats(Long viewer, Long targetUserId, Long teamId) {
        if (viewer.equals(targetUserId)) {
            return;
        }
        // 対象 userId が当該チームに所属していること（団体スコープの整合・02 §F.1）
        boolean targetInTeam = accessControlService.isMember(targetUserId, teamId, SCOPE_TEAM);
        if (!targetInTeam) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
        // (2) 閲覧者が当該チーム ADMIN/DEPUTY 以上
        if (accessControlService.isAdminOrAbove(viewer, teamId, SCOPE_TEAM)) {
            return;
        }
        // (3) F19.1 公開設定 ON ＋ 閲覧者が当該チームメンバー
        boolean publicProfile = userRepository.findById(targetUserId)
                .map(UserEntity::isPublicProfileEnabled)
                .orElse(false);
        if (publicProfile && accessControlService.isMember(viewer, teamId, SCOPE_TEAM)) {
            return;
        }
        throw new BusinessException(MatchErrorCode.MATCH_010);
    }

    private ResponseEntity<PagedResponse<UserMatchTimelineEntry>> timelineResponse(
            Long orgId, Long userId, Long teamId, LocalDateTime from, LocalDateTime to,
            MatchKind kind, Sport sport, int page, int size) {
        MatchStatsAggregationService.TimelinePage result =
                aggregationService.aggregateUserTimeline(orgId, userId, teamId, from, to, kind, sport, page, size);
        int totalPages = size > 0 ? (int) Math.ceil((double) result.total() / size) : 0;
        return ResponseEntity.ok(PagedResponse.of(result.entries(),
                new PagedResponse.PageMeta(result.total(), page, size, totalPages)));
    }
}
