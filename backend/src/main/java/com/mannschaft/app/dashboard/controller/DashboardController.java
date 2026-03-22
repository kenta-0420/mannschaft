package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.OrgDashboardResponse;
import com.mannschaft.app.dashboard.dto.PersonalDashboardResponse;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ダッシュボードコントローラー。
 * 個人・チーム・組織ダッシュボードの一括取得、個別ウィジェットデータ取得、
 * ウィジェット設定のCRUDを提供する。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardWidgetService widgetService;
    private final ActivityFeedService activityFeedService;

    // ============================================
    // 個人ダッシュボード
    // ============================================

    /**
     * 個人ダッシュボードの全ウィジェットデータを一括取得する。
     */
    @GetMapping
    @Operation(summary = "個人ダッシュボード一括取得",
            description = "ログインユーザーの個人ダッシュボードを取得する。priority=CRITICALで第1段階ウィジェットのみ高速返却")
    public ResponseEntity<ApiResponse<PersonalDashboardResponse>> getPersonalDashboard(
            @Parameter(description = "取得優先度（CRITICAL / ALL）") @RequestParam(defaultValue = "ALL") String priority) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalDashboardResponse response = dashboardService.getPersonalDashboard(userId, priority);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * お知らせ欄の詳細一覧（ページネーション対応）。
     */
    @GetMapping("/notices")
    @Operation(summary = "お知らせ一覧", description = "個人ダッシュボードのお知らせ欄（カーソルページネーション対応）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNotices(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) Boolean isRead) {
        // TODO: notifications テーブル連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "items", List.of(),
                "meta", Map.of("next_cursor", 0, "limit", limit, "total_count", 0, "has_next", false)
        )));
    }

    /**
     * 自分の投稿一覧（ページネーション対応）。
     */
    @GetMapping("/my-posts")
    @Operation(summary = "自分の投稿一覧", description = "自分のタイムライン投稿一覧（カーソルページネーション対応）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        // TODO: timeline_posts 連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "items", List.of(),
                "meta", Map.of("next_cursor", 0, "limit", limit, "total_count", 0, "has_next", false)
        )));
    }

    /**
     * 直近イベント + 出欠状況。
     */
    @GetMapping("/upcoming-events")
    @Operation(summary = "直近イベント", description = "今後N日間のイベント + 出欠状況を横断取得")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUpcomingEvents(
            @RequestParam(defaultValue = "7") Integer days) {
        // TODO: schedules + schedule_attendances 連携
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }

    /**
     * 未読スレッド一覧。
     */
    @GetMapping("/unread-threads")
    @Operation(summary = "未読スレッド一覧", description = "未読の掲示板スレッド + チャットチャネルを横断取得")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUnreadThreads(
            @RequestParam(defaultValue = "10") Integer limit) {
        // TODO: bulletin_threads + chat_channel_members 連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "bulletin_threads", List.of(),
                "chat_channels", List.of(),
                "total_unread_bulletin", 0,
                "total_unread_chat", 0
        )));
    }

    /**
     * 最近のアクティビティ。
     */
    @GetMapping("/activity")
    @Operation(summary = "最近のアクティビティ", description = "所属チーム/組織を横断した最近の活動フィード")
    public ResponseEntity<ApiResponse<List<ActivityFeedResponse>>> getActivity(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") Integer limit) {
        Long userId = SecurityUtils.getCurrentUserId();
        // TODO: team_memberships から所属スコープIDを取得
        List<Long> scopeIds = List.of();
        List<ActivityFeedResponse> response = activityFeedService.getActivityFeed(userId, cursor, limit, scopeIds);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人カレンダーサマリー。
     */
    @GetMapping("/calendar")
    @Operation(summary = "個人カレンダーサマリー", description = "個人スケジュール + 所属チームの公開イベントを集約")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCalendar(
            @RequestParam(required = false) String month) {
        // TODO: schedules 連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "events_today", 0,
                "events_this_week", 0,
                "events_this_month", 0,
                "days_with_events", List.of()
        )));
    }

    /**
     * パフォーマンスサマリー。
     */
    @GetMapping("/performance")
    @Operation(summary = "パフォーマンスサマリー", description = "所属チーム/組織ごとの個人パフォーマンス概要")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPerformance() {
        // TODO: パフォーマンス管理モジュール連携
        return ResponseEntity.ok(ApiResponse.of(Map.of("teams", List.of())));
    }

    /**
     * チャットハブデータ取得。
     */
    @GetMapping("/chat-hub")
    @Operation(summary = "チャットハブ", description = "チーム別自動グルーピング + カスタムフォルダ別のチャット一覧")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getChatHub(
            @RequestParam(defaultValue = "false") Boolean allTeams) {
        // TODO: chat_channels + chat_contact_folders 連携
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "team_groups", List.of(),
                "custom_folders", List.of(),
                "uncategorized_dms", List.of(),
                "summary", Map.of("total_unread", 0, "total_dms", 0, "total_contacts", 0)
        )));
    }

    // ============================================
    // チーム・組織ダッシュボード
    // ============================================

    /**
     * チームダッシュボード一括取得。
     */
    @GetMapping("/team/{teamId}")
    @Operation(summary = "チームダッシュボード一括取得", description = "チーム全体の活動状況・お知らせ・イベント等を一括取得")
    public ResponseEntity<ApiResponse<TeamDashboardResponse>> getTeamDashboard(
            @PathVariable Long teamId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long userId = SecurityUtils.getCurrentUserId();
        TeamDashboardResponse response = dashboardService.getTeamDashboard(userId, teamId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織ダッシュボード一括取得。
     */
    @GetMapping("/organization/{orgId}")
    @Operation(summary = "組織ダッシュボード一括取得", description = "傘下チーム一覧・組織全体の統計等を一括取得")
    public ResponseEntity<ApiResponse<OrgDashboardResponse>> getOrgDashboard(
            @PathVariable Long orgId,
            @Parameter(description = "統計期間（TODAY / WEEK / MONTH）") @RequestParam(defaultValue = "WEEK") String statsPeriod) {
        Long userId = SecurityUtils.getCurrentUserId();
        OrgDashboardResponse response = dashboardService.getOrgDashboard(userId, orgId, statsPeriod);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ============================================
    // ウィジェット設定
    // ============================================

    /**
     * ウィジェット設定一覧を取得する。
     */
    @GetMapping("/widgets")
    @Operation(summary = "ウィジェット設定一覧", description = "指定スコープのウィジェット設定一覧を取得する")
    public ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> getWidgetSettings(
            @RequestParam String scopeType,
            @RequestParam(required = false) Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        // TODO: isAdmin判定。現時点では true で返却
        List<WidgetSettingResponse> response = widgetService.getWidgetSettings(userId, parsed, resolvedScopeId, true);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ウィジェット設定を一括更新する（UPSERT）。
     */
    @PutMapping("/widgets")
    @Operation(summary = "ウィジェット設定一括更新", description = "ウィジェットの表示/非表示・並び順を一括更新する")
    public ResponseEntity<ApiResponse<List<WidgetSettingResponse>>> updateWidgetSettings(
            @Valid @RequestBody UpdateWidgetSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<WidgetSettingResponse> response = widgetService.updateWidgetSettings(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ウィジェット設定をリセットする。
     */
    @DeleteMapping("/widgets")
    @Operation(summary = "ウィジェット設定リセット", description = "指定スコープの全設定を削除しデフォルトに復帰する")
    public ResponseEntity<Void> resetWidgetSettings(
            @RequestParam String scopeType,
            @RequestParam(required = false) Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeType parsed = widgetService.parseScopeType(scopeType);
        Long resolvedScopeId = widgetService.resolveScopeId(parsed, scopeId);
        widgetService.resetWidgetSettings(userId, parsed, resolvedScopeId);
        return ResponseEntity.noContent().build();
    }
}
